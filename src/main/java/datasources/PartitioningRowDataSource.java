package datasources;

import datasources.utils.DBClientWrapper;
import datasources.utils.DBTableReader;
import edb.common.Split;
import edb.common.UnknownTableException;
import org.apache.log4j.Logger;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.sources.v2.DataSourceOptions;
import org.apache.spark.sql.sources.v2.DataSourceV2;
import org.apache.spark.sql.sources.v2.ReadSupport;
import org.apache.spark.sql.sources.v2.reader.DataReader;
import org.apache.spark.sql.sources.v2.reader.DataReaderFactory;
import org.apache.spark.sql.sources.v2.reader.DataSourceReader;
import org.apache.spark.sql.sources.v2.reader.SupportsReportPartitioning;
import org.apache.spark.sql.sources.v2.reader.partitioning.ClusteredDistribution;
import org.apache.spark.sql.sources.v2.reader.partitioning.Distribution;
import org.apache.spark.sql.sources.v2.reader.partitioning.Partitioning;
import org.apache.spark.sql.types.StructType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Another simple DataSource that supports parallel reads (i.e.: on multiple executors)
 * from the ExampleDB. It gets a table name from its configuration and infers a schema from
 * that table. If a number of partitions is specified in properties, it is used. Otherwise,
 * the table's default partition count (always 4 in ExampleDB) is used.
 */
public class PartitioningRowDataSource implements DataSourceV2, ReadSupport {

    static Logger log = Logger.getLogger(PartitioningRowDataSource.class.getName());

    /**
     * Spark calls this to create the reader. Notice how it pulls the host and port
     * on which ExampleDB is listening, as well as a table name, from the supplied options.
     * @param options
     * @return
     */
    @Override
    public DataSourceReader createReader(DataSourceOptions options) {
        String host = options.get("host").orElse("localhost");
        int port = options.getInt("port", -1);
        String table = options.get("table").orElse("unknownTable"); // TODO: throw
        int partitions = Integer.parseInt(options.get("partitions").orElse("0"));
        return new Reader(host, port, table, partitions);
    }

    /**
     * This is how Spark discovers the source's schema by requesting a schema from ExmapleDB,
     * and how it obtains the reader factories to be used by the executors to create readers.
     * Notice that one factory is created for each partition.
     */
    class Reader implements DataSourceReader, SupportsReportPartitioning {

        public Reader(String host, int port, String table, int partitions) {
            _host = host;
            _port = port;
            _table = table;
            _partitions = partitions;
        }

        private StructType _schema;
        private String _host;
        private int _port;
        private String _table;
        private int _partitions;

        @Override
        public StructType readSchema() {
            if (_schema == null) {
                DBClientWrapper db = new DBClientWrapper(_host, _port);
                db.connect();
                try {
                    _schema = db.getSchema(_table);
                } catch (UnknownTableException ute) {
                    throw new RuntimeException(ute);
                } finally {
                    db.disconnect();
                }
            }
            return _schema;
        }

        @Override
        public List<DataReaderFactory<Row>> createDataReaderFactories() {
            List<Split> splits = null;
            DBClientWrapper db = new DBClientWrapper(_host, _port);
            db.connect();
            try {
                if (_partitions == 0)
                    splits = db.getSplits(_table);
                else
                    splits = db.getSplits(_table, _partitions);
            } catch (UnknownTableException ute) {
                throw new RuntimeException(ute);
            } finally {
                db.disconnect();
            }
            List<DataReaderFactory<Row>> factories = new ArrayList<>();
            for (Split split : splits) {
                DataReaderFactory<Row> factory =
                        new SplitDataReaderFactory(_host, _port, _table, readSchema(), split);
                factories.add(factory);
            }
            return factories;
        }

        @Override
        public Partitioning outputPartitioning() {
            return new TrivialPartitioning();
        }
    }

    static class TrivialPartitioning implements Partitioning {

        static Logger log = Logger.getLogger(TrivialPartitioning.class.getName());

        @Override
        public int numPartitions() {
            log.info("asked for numPartitions");
            return 8;
        }

        @Override
        public boolean satisfy(Distribution distribution) {
            log.info("asked to satisfy");
            // can't satisfy any Distribution
            return false;
        }
    }

    static class SingleClusteredColumnPartitioning implements Partitioning {

        public SingleClusteredColumnPartitioning(String columnName, int partitions) {
            _columnName = columnName;
            _partitions = partitions;
        }

        @Override
        public int numPartitions() {
            return _partitions;
        }

        @Override
        public boolean satisfy(Distribution distribution) {
            //
            // Since Spark may add other Distribution policies in the future, we can't assume
            // it's always a ClusteredDistribution
            //
            if (distribution instanceof ClusteredDistribution) {
                String[] clusteredCols = ((ClusteredDistribution) distribution).clusteredColumns;
                return Arrays.asList(clusteredCols).contains(_columnName);
            }

            return false;
        }

        private String _columnName;
        private int _partitions;
    }

    /**
     * This is used by each executor to read from ExampleDB. It uses the Split to know
     * which data to read.
     * Also note that when DBClientWrapper's getTableReader() method is called
     * it reads ALL the data in its own Split eagerly.
     */
    static class TaskDataReader implements DataReader<Row> {

        static Logger log = Logger.getLogger(TaskDataReader.class.getName());

        public TaskDataReader(String host, int port, String table,
                              StructType schema, Split split)
                throws UnknownTableException {
            log.info("Task reading from [" + host + ":" + port + "]" );
            _db = new DBClientWrapper(host, port);
            _db.connect();
            _reader = _db.getTableReader(table, schema.fieldNames(), split);
        }

        private DBClientWrapper _db;

        private DBTableReader _reader;

        @Override
        public boolean next() {
            return _reader.next();
        }

        @Override
        public Row get() {
            return _reader.get();
        }

        @Override
        public void close() throws IOException {
            _db.disconnect();
        }
    }

    /**
     * Note that this has to be serializable. Each instance is sent to an executor,
     * which uses it to create a reader for its own use.
     */
    static class SplitDataReaderFactory implements DataReaderFactory<Row> {

        public SplitDataReaderFactory(String host, int port,
                                       String table, StructType schema,
                                       Split split) {
            _host = host;
            _port = port;
            _table = table;
            _schema = schema;
            _split = split;
        }

        private String _host;
        private int _port;
        private String _table;
        private StructType _schema;
        private Split _split;

        @Override
        public DataReader<Row> createDataReader() {
            log.info("Factory creating reader for [" + _host + ":" + _port + "]" );
            try {
                return new TaskDataReader(_host, _port, _table, _schema, _split);
            } catch (UnknownTableException ute) {
                throw new RuntimeException(ute);
            }
        }

    }


}
