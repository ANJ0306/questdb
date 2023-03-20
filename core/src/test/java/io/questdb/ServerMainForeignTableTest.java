/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2023 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb;

import io.questdb.cairo.*;
import io.questdb.cairo.pool.PoolListener;
import io.questdb.cairo.sql.OperationFuture;
import io.questdb.cairo.sql.RecordCursor;
import io.questdb.cairo.sql.RecordCursorFactory;
import io.questdb.griffin.CompiledQuery;
import io.questdb.griffin.SqlCompiler;
import io.questdb.griffin.SqlException;
import io.questdb.griffin.SqlExecutionContext;
import io.questdb.log.Log;
import io.questdb.log.LogFactory;
import io.questdb.mp.SOCountDownLatch;
import io.questdb.std.Files;
import io.questdb.std.Misc;
import io.questdb.std.Os;
import io.questdb.std.str.Path;
import io.questdb.std.str.StringSink;
import io.questdb.test.tools.TestUtils;
import org.junit.*;

import java.io.IOException;
import java.nio.file.Paths;
import java.sql.*;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.questdb.test.tools.TestUtils.*;

public class ServerMainForeignTableTest extends AbstractBootstrapTest {

    private static final Log LOG = LogFactory.getLog(ServerMainForeignTableTest.class);

    private static final String TABLE_START_CONTENT = "min\tmax\tcount\n" +
            "2023-01-01T00:00:00.950399Z\t2023-01-01T23:59:59.822691Z\t90909\n" +
            "2023-01-02T00:00:00.773090Z\t2023-01-02T23:59:59.645382Z\t90909\n" +
            "2023-01-03T00:00:00.595781Z\t2023-01-03T23:59:59.468073Z\t90909\n" +
            "2023-01-04T00:00:00.418472Z\t2023-01-04T23:59:59.290764Z\t90909\n" +
            "2023-01-05T00:00:00.241163Z\t2023-01-05T23:59:59.113455Z\t90909\n" +
            "2023-01-06T00:00:00.063854Z\t2023-01-06T23:59:59.886545Z\t90910\n" +
            "2023-01-07T00:00:00.836944Z\t2023-01-07T23:59:59.709236Z\t90909\n" +
            "2023-01-08T00:00:00.659635Z\t2023-01-08T23:59:59.531927Z\t90909\n" +
            "2023-01-09T00:00:00.482326Z\t2023-01-09T23:59:59.354618Z\t90909\n" +
            "2023-01-10T00:00:00.305017Z\t2023-01-10T23:59:59.177309Z\t90909\n" +
            "2023-01-11T00:00:00.127708Z\t2023-01-11T23:59:59.000000Z\t90909\n";
    private static final String firstPartitionName = "2023-01-01";
    private static final String otherVolumeAlias = "SECONDARY VOLUME";
    private static final int partitionCount = 11;
    private static final int pgPortDelta = 10;
    private static final int pgPort = PG_PORT + pgPortDelta;
    private static String mainVolume;
    private static String otherVolume;


    @BeforeClass
    public static void setUpStatic() throws Exception {
        AbstractBootstrapTest.setUpStatic();
        mainVolume = dbPath.toString();
        try {
            Files.remove(dbPath.concat("sys.column_versions_purge_log.lock").$());
            Files.remove(dbPath.trimTo(dbPathLen).concat("telemetry_config.lock").$());
            otherVolume = AbstractBootstrapTest.temp.newFolder("path", "to", "wherever").getAbsolutePath();
            createDummyConfiguration(
                    HTTP_PORT + pgPortDelta,
                    HTTP_MIN_PORT + pgPortDelta,
                    pgPort,
                    ILP_PORT + pgPortDelta,
                    PropertyKey.CAIRO_WAL_SUPPORTED.getPropertyPath() + "=true",
                    PropertyKey.CAIRO_VOLUMES.getPropertyPath() + '=' + otherVolumeAlias + "->" + otherVolume);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @AfterClass
    public static void tearDownStatic() throws Exception {
        Assert.assertEquals(0, Files.rmdir(auxPath.of(otherVolume).$()));
        AbstractBootstrapTest.tearDownStatic();
    }

    @Test
    public void testServerMainCreateTableInVolumeIfNotExists() throws Exception {
        Assume.assumeFalse(Os.isWindows()); // Windows requires special privileges to create soft links
        String tableName = testName.getMethodName();
        assertMemoryLeak(() -> {
            try (
                    ServerMain qdb = new ServerMain("-d", rootDir, Bootstrap.SWITCH_USE_DEFAULT_LOG_FACTORY_CONFIGURATION);
                    SqlCompiler compiler = new SqlCompiler(qdb.getCairoEngine());
                    SqlExecutionContext context = executionContext(qdb.getCairoEngine())
            ) {
                qdb.start();
                CairoEngine engine = qdb.getCairoEngine();
                CairoConfiguration cairoConfig = qdb.getConfiguration().getCairoConfiguration();

                // create non wal table in volume, and drop it
                TableToken tableToken = createPopulateTable(cairoConfig, engine, compiler, context, tableName, false, true, false);
                assertTableExists(tableToken, false, true);
                createPopulateTable(cairoConfig, engine, compiler, context, tableName, false, true, true);
                dropTable(engine, compiler, context, tableToken, false, otherVolume);

                // create non wal table in standard dir, and drop it
                tableToken = createPopulateTable(cairoConfig, engine, compiler, context, tableName, false, false, false);
                assertTableExists(tableToken, false, false);
                dropTable(engine, compiler, context, tableToken, false, otherVolume);
                try {
                    dropTable(engine, compiler, context, tableToken, false, null);
                    Assert.fail();
                } catch (SqlException err) {
                    TestUtils.assertContains(err.getFlyweightMessage(), "table does not exist [table=" + tableName + ']');
                }
            }
        });
    }

    @Test
    public void testServerMainCreateTableInVolumeTableExists0() throws Exception {
        Assume.assumeFalse(Os.isWindows()); // Windows requires special privileges to create soft links
        String tableName = testName.getMethodName();
        assertMemoryLeak(() -> {
            try (
                    ServerMain qdb = new ServerMain("-d", rootDir, Bootstrap.SWITCH_USE_DEFAULT_LOG_FACTORY_CONFIGURATION);
                    SqlCompiler compiler = new SqlCompiler(qdb.getCairoEngine());
                    SqlExecutionContext context = executionContext(qdb.getCairoEngine())
            ) {
                qdb.start();
                CairoConfiguration cairoConfig = qdb.getConfiguration().getCairoConfiguration();
                CairoEngine engine = qdb.getCairoEngine();

                // create normal table in standard dir, then drop it
                TableToken tableToken = createPopulateTable(cairoConfig, engine, compiler, context, tableName, false, false, false);
                try {
                    createPopulateTable(cairoConfig, engine, compiler, context, tableName, false, true, false);
                    Assert.fail();
                } catch (SqlException e) {
                    TestUtils.assertContains(e.getFlyweightMessage(), "table already exists");
                }
                assertTableExists(tableToken, false, false);
                dropTable(engine, compiler, context, tableToken, false, null);

                // create normal table in other volume, then drop it
                tableToken = createPopulateTable(cairoConfig, engine, compiler, context, tableName, false, true, false);
                try {
                    createPopulateTable(cairoConfig, engine, compiler, context, tableName, false, false, false);
                    Assert.fail();
                } catch (SqlException e) {
                    TestUtils.assertContains(e.getFlyweightMessage(), "table already exists");
                }
                assertTableExists(tableToken, false, true);
                dropTable(engine, compiler, context, tableToken, false, otherVolume);
            }
        });
    }

    @Test
    public void testServerMainCreateTableInVolumeTableExists1() throws Exception {
        Assume.assumeFalse(Os.isWindows()); // Windows requires special privileges to create soft links
        String tableName = testName.getMethodName();
        assertMemoryLeak(() -> {
            try (
                    ServerMain qdb = new ServerMain("-d", rootDir, Bootstrap.SWITCH_USE_DEFAULT_LOG_FACTORY_CONFIGURATION);
                    SqlCompiler compiler = new SqlCompiler(qdb.getCairoEngine());
                    SqlExecutionContext context = executionContext(qdb.getCairoEngine())
            ) {
                qdb.start();
                CairoConfiguration cairoConfig = qdb.getConfiguration().getCairoConfiguration();
                CairoEngine engine = qdb.getCairoEngine();

                // create normal table in other volume, then drop it
                TableToken tableToken = createPopulateTable(cairoConfig, engine, compiler, context, tableName, false, true, false);
                try {
                    createPopulateTable(cairoConfig, engine, compiler, context, tableName, false, false, false);
                    Assert.fail();
                } catch (SqlException e) {
                    TestUtils.assertContains(e.getFlyweightMessage(), "table already exists");
                }
                assertTableExists(tableToken, false, true);
                dropTable(engine, compiler, context, tableToken, false, otherVolume);

                // create normal table in standard dir, then drop it
                tableToken = createPopulateTable(cairoConfig, engine, compiler, context, tableName, false, false, false);
                try {
                    createPopulateTable(cairoConfig, engine, compiler, context, tableName, false, true, false);
                    Assert.fail();
                } catch (SqlException e) {
                    TestUtils.assertContains(e.getFlyweightMessage(), "table already exists");
                }
                assertTableExists(tableToken, false, false);
                dropTable(engine, compiler, context, tableToken, false, null);
            }
        });
    }

    @Test
    public void testServerMainCreateTableMoveItsFolderAwayAndSoftLinkIt() throws Exception {
        Assume.assumeFalse(Os.isWindows()); // Windows requires special privileges to create soft links
        String tableName = testName.getMethodName();
        assertMemoryLeak(() -> {
            // create table with some data
            try (
                    ServerMain qdb = new ServerMain("-d", rootDir, Bootstrap.SWITCH_USE_DEFAULT_LOG_FACTORY_CONFIGURATION);
                    SqlCompiler compiler = new SqlCompiler(qdb.getCairoEngine());
                    SqlExecutionContext context = executionContext(qdb.getCairoEngine())
            ) {
                qdb.start();
                CairoEngine engine = qdb.getCairoEngine();
                CairoConfiguration cairoConfig = qdb.getConfiguration().getCairoConfiguration();
                TableToken tableToken = createPopulateTable(cairoConfig, engine, compiler, context, tableName, false, true, false);
                assertSql(
                        compiler,
                        context,
                        "SELECT min(ts), max(ts), count() FROM " + tableName + " SAMPLE BY 1d ALIGN TO CALENDAR",
                        new StringSink(),
                        TABLE_START_CONTENT);
                assertTableExists(tableToken, false, true);
            }

            // copy the table to a foreign location, remove it, then symlink it
            try (
                    Path filePath = new Path().of(rootDir).concat(PropServerConfiguration.DB_DIRECTORY).concat(TableUtils.TAB_INDEX_FILE_NAME).$();
                    Path fakeTablePath = new Path().of(rootDir).concat(PropServerConfiguration.DB_DIRECTORY).concat("coconut").$();
                    Path foreignPath = new Path().of(rootDir).concat("banana").concat(tableName).slash$()
            ) {
                if (!Files.exists(foreignPath)) {
                    Assert.assertEquals(0, Files.mkdirs(foreignPath, 509));
                }
                Assert.assertTrue(Files.exists(foreignPath));
                dbPath.trimTo(dbPathLen).concat(tableName).$();
                TestUtils.copyDirectory(dbPath, foreignPath, 509);

                String tablePathStr = dbPath.toString();
                String foreignPathStr = foreignPath.toString();
                Assert.assertEquals(0, Files.rmdir(auxPath.of(tablePathStr).$()));
                Assert.assertFalse(Files.exists(dbPath));
                createSoftLink(foreignPathStr, tablePathStr);
                Assert.assertTrue(Files.exists(dbPath));

                if (!Files.exists(fakeTablePath)) {
                    createSoftLink(filePath.toString(), fakeTablePath.toString());
                }
                Assert.assertTrue(Files.exists(fakeTablePath));
            }

            // check content of table after sym-linking it
            try (
                    ServerMain qdb = new ServerMain("-d", rootDir, Bootstrap.SWITCH_USE_DEFAULT_LOG_FACTORY_CONFIGURATION);
                    SqlCompiler compiler = new SqlCompiler(qdb.getCairoEngine());
                    SqlExecutionContext context = executionContext(qdb.getCairoEngine())
            ) {
                qdb.start();
                assertSql(
                        compiler,
                        context,
                        "SELECT min(ts), max(ts), count() FROM " + tableName + " SAMPLE BY 1d ALIGN TO CALENDAR",
                        new StringSink(),
                        TABLE_START_CONTENT);
                CairoEngine engine = qdb.getCairoEngine();
                TableToken tableToken = engine.getTableToken(tableName);
                assertTableExists(tableToken, false, true);
                dropTable(engine, compiler, context, tableToken, false, otherVolume);
            }
        });
    }

    @Test
    public void testServerMainCreateTableWhileConcurrentCreateTable() throws Exception {
        Assume.assumeFalse(Os.isWindows()); // Windows requires special privileges to create soft links
        String tableName = testName.getMethodName();
        assertMemoryLeak(() -> {
            try (
                    ServerMain qdb = new ServerMain("-d", rootDir, Bootstrap.SWITCH_USE_DEFAULT_LOG_FACTORY_CONFIGURATION);
                    SqlCompiler compiler0 = new SqlCompiler(qdb.getCairoEngine());
                    SqlCompiler compiler1 = new SqlCompiler(qdb.getCairoEngine());
                    SqlExecutionContext context0 = executionContext(qdb.getCairoEngine());
                    SqlExecutionContext context1 = executionContext(qdb.getCairoEngine())
            ) {
                qdb.start();
                CairoEngine engine = qdb.getCairoEngine();
                CairoConfiguration cairoConfig = qdb.getConfiguration().getCairoConfiguration();
                CyclicBarrier startBarrier = new CyclicBarrier(3);
                SOCountDownLatch haltLatch = new SOCountDownLatch();
                AtomicBoolean isInVolume = new AtomicBoolean();
                dbPath.trimTo(dbPathLen).concat(tableName).$();
                for (int i = 0; i < 4; i++) {
                    isInVolume.set(false);
                    startBarrier.reset();
                    haltLatch.setCount(2);
                    concurrentTableCreator(
                            "createTable",
                            engine,
                            cairoConfig,
                            compiler0,
                            context0,
                            startBarrier,
                            haltLatch,
                            tableName,
                            false,
                            false,
                            isInVolume
                    ).start();
                    concurrentTableCreator(
                            "createTableInVolume",
                            engine,
                            cairoConfig,
                            compiler1,
                            context1,
                            startBarrier,
                            haltLatch,
                            tableName,
                            false,
                            true,
                            isInVolume
                    ).start();
                    startBarrier.await();
                    haltLatch.await();
                    dropTable(
                            engine,
                            compiler0,
                            context0,
                            engine.getTableToken(tableName),
                            false,
                            isInVolume.get() ? otherVolume : null);
                }
            }
        });
    }

    @Test
    public void testServerMainCreateWalTableIfNotExistsInVolumeTableExists() throws Exception {
        Assume.assumeFalse(Os.isWindows()); // Windows requires special privileges to create soft links
        String tableName = testName.getMethodName();
        assertMemoryLeak(() -> {
            try (
                    ServerMain qdb = new ServerMain("-d", rootDir, Bootstrap.SWITCH_USE_DEFAULT_LOG_FACTORY_CONFIGURATION);
                    SqlCompiler compiler = new SqlCompiler(qdb.getCairoEngine());
                    SqlExecutionContext context = executionContext(qdb.getCairoEngine())
            ) {
                qdb.start();
                CairoConfiguration cairoConfig = qdb.getConfiguration().getCairoConfiguration();
                CairoEngine engine = qdb.getCairoEngine();

                TableToken tableToken = createPopulateTable(cairoConfig, engine, compiler, context, tableName, true, true, false);
                assertTableExists(tableToken, true, true);
                tableToken = createPopulateTable(cairoConfig, engine, compiler, context, tableName, true, true, true);
                dropTable(engine, compiler, context, tableToken, true, otherVolume);

                tableToken = createPopulateTable(cairoConfig, engine, compiler, context, tableName, true, true, true);
                assertTableExists(tableToken, true, true);
                dropTable(engine, compiler, context, tableToken, true, otherVolume);
            }
        });
    }

    @Test
    public void testServerMainCreateWalTableInVolume() throws Exception {
        Assume.assumeFalse(Os.isWindows()); // Windows requires special privileges to create soft links
        String tableName = testName.getMethodName();
        assertMemoryLeak(() -> {
            try (
                    ServerMain qdb = new TestServerMain("-d", rootDir, Bootstrap.SWITCH_USE_DEFAULT_LOG_FACTORY_CONFIGURATION);
                    SqlCompiler compiler = new SqlCompiler(qdb.getCairoEngine());
                    SqlExecutionContext context = executionContext(qdb.getCairoEngine())
            ) {
                qdb.start();
                CairoEngine engine = qdb.getCairoEngine();
                TableToken tableToken = createPopulateTable(qdb.getConfiguration().getCairoConfiguration(), engine, compiler, context, tableName, true, true, false);
                assertTableExists(tableToken, true, true);
                assertSql(
                        compiler,
                        context,
                        "SELECT min(ts), max(ts), count() FROM " + tableName + " SAMPLE BY 1d ALIGN TO CALENDAR",
                        new StringSink(),
                        TABLE_START_CONTENT);
                dropTable(engine, compiler, context, tableToken, true, otherVolume);
            }
        });
    }

    @Test
    public void testServerMainCreateWalTableInVolumeTableExists0() throws Exception {
        Assume.assumeFalse(Os.isWindows()); // Windows requires special privileges to create soft links
        String tableName = testName.getMethodName();
        assertMemoryLeak(() -> {
            try (
                    ServerMain qdb = new ServerMain("-d", rootDir, Bootstrap.SWITCH_USE_DEFAULT_LOG_FACTORY_CONFIGURATION);
                    SqlCompiler compiler = new SqlCompiler(qdb.getCairoEngine());
                    SqlExecutionContext context = executionContext(qdb.getCairoEngine())
            ) {
                qdb.start();
                CairoEngine engine = qdb.getCairoEngine();
                CairoConfiguration cairoConfig = qdb.getConfiguration().getCairoConfiguration();

                TableToken tableToken = createPopulateTable(cairoConfig, engine, compiler, context, tableName, true, false, false);
                try {
                    createPopulateTable(cairoConfig, engine, compiler, context, tableName, true, true, false);
                    Assert.fail();
                } catch (SqlException e) {
                    TestUtils.assertContains(e.getFlyweightMessage(), "table already exists");
                }
                assertTableExists(tableToken, true, false);
                dropTable(engine, compiler, context, tableToken, true, null);

                tableToken = createPopulateTable(cairoConfig, engine, compiler, context, tableName, true, true, false);
                try {
                    createPopulateTable(cairoConfig, engine, compiler, context, tableName, true, false, false);
                    Assert.fail();
                } catch (SqlException e) {
                    TestUtils.assertContains(e.getFlyweightMessage(), "table already exists");
                }
                assertTableExists(tableToken, true, true);
                dropTable(engine, compiler, context, tableToken, true, otherVolume);
            }
        });
    }

    @Test
    public void testServerMainCreateWalTableInVolumeTableExists1() throws Exception {
        Assume.assumeFalse(Os.isWindows()); // Windows requires special privileges to create soft links
        String tableName = testName.getMethodName();
        assertMemoryLeak(() -> {
            try (
                    ServerMain qdb = new ServerMain("-d", rootDir, Bootstrap.SWITCH_USE_DEFAULT_LOG_FACTORY_CONFIGURATION);
                    SqlCompiler compiler = new SqlCompiler(qdb.getCairoEngine());
                    SqlExecutionContext context = executionContext(qdb.getCairoEngine())
            ) {
                qdb.start();
                CairoEngine engine = qdb.getCairoEngine();
                CairoConfiguration cairoConfig = qdb.getConfiguration().getCairoConfiguration();

                TableToken tableToken = createPopulateTable(cairoConfig, engine, compiler, context, tableName, true, true, false);
                try {
                    createPopulateTable(cairoConfig, engine, compiler, context, tableName, true, false, false);
                    Assert.fail();
                } catch (SqlException e) {
                    TestUtils.assertContains(e.getFlyweightMessage(), "table already exists");
                }
                assertTableExists(tableToken, true, true);
                dropTable(engine, compiler, context, tableToken, true, otherVolume);

                tableToken = createPopulateTable(cairoConfig, engine, compiler, context, tableName, true, false, false);
                try {
                    createPopulateTable(cairoConfig, engine, compiler, context, tableName, true, true, false);
                    Assert.fail();
                } catch (SqlException e) {
                    TestUtils.assertContains(e.getFlyweightMessage(), "table already exists");
                }
                assertTableExists(tableToken, true, false);
                dropTable(engine, compiler, context, tableToken, true, null);
            }
        });
    }

    @Test
    public void testServerMainCreateWalTableWhileConcurrentCreateWalTable() throws Exception {
        Assume.assumeFalse(Os.isWindows()); // Windows requires special privileges to create soft links
        String tableName = testName.getMethodName();
        assertMemoryLeak(() -> {
            try (
                    ServerMain qdb = new ServerMain("-d", rootDir, Bootstrap.SWITCH_USE_DEFAULT_LOG_FACTORY_CONFIGURATION);
                    SqlCompiler compiler0 = new SqlCompiler(qdb.getCairoEngine());
                    SqlCompiler compiler1 = new SqlCompiler(qdb.getCairoEngine());
                    SqlExecutionContext context0 = executionContext(qdb.getCairoEngine());
                    SqlExecutionContext context1 = executionContext(qdb.getCairoEngine())
            ) {
                qdb.start();
                CairoEngine engine = qdb.getCairoEngine();
                CairoConfiguration cairoConfig = qdb.getConfiguration().getCairoConfiguration();
                CyclicBarrier startBarrier = new CyclicBarrier(3);
                SOCountDownLatch haltLatch = new SOCountDownLatch(2);
                AtomicBoolean isInVolume = new AtomicBoolean();

                for (int i = 0; i < 1; i++) {
                    isInVolume.set(false);
                    startBarrier.reset();
                    haltLatch.setCount(2);
                    concurrentTableCreator(
                            "createWalTable",
                            engine,
                            cairoConfig,
                            compiler0,
                            context0,
                            startBarrier,
                            haltLatch,
                            tableName,
                            true,
                            false,
                            isInVolume
                    ).start();
                    concurrentTableCreator(
                            "createWalTableInVolume",
                            engine,
                            cairoConfig,
                            compiler1,
                            context1,
                            startBarrier,
                            haltLatch,
                            tableName,
                            true,
                            true,
                            isInVolume
                    ).start();
                    startBarrier.await();
                    haltLatch.await();
                    dropTable(
                            engine,
                            compiler0,
                            context0,
                            engine.getTableToken(tableName),
                            true,
                            isInVolume.get() ? otherVolume : null);
                }
            }
        });
    }

    private static void assertTableExists(TableToken tableToken, boolean isWal, boolean inVolume) throws Exception {
        StringSink resultSink = new StringSink();
        try (
                Connection conn = DriverManager.getConnection(getPgConnectionUri(pgPort), PG_CONNECTION_PROPERTIES);
                PreparedStatement stmt = conn.prepareStatement("tables()");
                ResultSet result = stmt.executeQuery()
        ) {
            ResultSetMetaData meta = result.getMetaData();
            int colCount = meta.getColumnCount();
            Assert.assertEquals(8, colCount);
            while (result.next()) {
                for (int i = 1; i <= colCount; i++) {
                    switch (meta.getColumnType(i)) {
                        case Types.BIT:
                            resultSink.put(result.getBoolean(i));
                            break;
                        case Types.INTEGER:
                            resultSink.put(result.getInt(i));
                            break;
                        case Types.BIGINT:
                            resultSink.put(result.getLong(i));
                            break;
                        case Types.VARCHAR:
                            resultSink.put(result.getString(i));
                            break;
                        default:
                            Assert.fail("unexpected type: " + meta.getColumnType(i));
                    }
                    resultSink.put('\t');
                }
                resultSink.clear(resultSink.length() - 1);
            }
            String expected = tableToken.getTableName() + "\tts\tDAY\t500000\t600000000\t" + isWal + '\t' + tableToken.getDirName();
            if (inVolume) {
                expected += " (->)";
            }
            TestUtils.assertContains(resultSink.toString(), expected);
        }
        Assert.assertTrue(Files.exists(auxPath.of(inVolume ? otherVolume : mainVolume).concat(tableToken.getDirName()).$()));
    }

    private static void assertTableExists(
            SqlCompiler compiler,
            SqlExecutionContext context,
            TableToken tableToken,
            boolean inVolume,
            boolean isWal
    ) throws Exception {
        StringSink resultSink = new StringSink();
        CompiledQuery cc = compiler.compile("tables()", context);
        try (
                RecordCursorFactory factory = cc.getRecordCursorFactory();
                RecordCursor cursor = factory.getCursor(context)
        ) {
            TestUtils.printCursor(cursor, factory.getMetadata(), true, resultSink, printer);
            String expected = tableToken.getTableName() + "\tts\tDAY\t500000\t600000000\t" + isWal + '\t' + tableToken.getDirName();
            if (inVolume) {
                expected += " (->)";
            }
            TestUtils.assertContains(resultSink.toString(), expected);
        }
        Assert.assertTrue(Files.exists(auxPath.of(inVolume ? otherVolume : mainVolume).concat(tableToken.getDirName()).$()));
    }

    private static void createSoftLink(String foreignPath, String tablePath) throws IOException {
        java.nio.file.Files.createSymbolicLink(Paths.get(tablePath), Paths.get(foreignPath));
    }

    private Thread concurrentTableCreator(
            String threadName,
            CairoEngine engine,
            CairoConfiguration cairoConfig,
            SqlCompiler compiler,
            SqlExecutionContext context,
            CyclicBarrier startBarrier,
            SOCountDownLatch haltLatch,
            String tableName,
            boolean isWal,
            boolean isInVolume,
            AtomicBoolean winnerIsInVolume
    ) {
        return new Thread(() -> {
            try {
                startBarrier.await();
                createPopulateTable(cairoConfig, engine, compiler, context, tableName, isWal, isInVolume, false);
                assertTableExists(compiler, context, engine.getTableToken(tableName), isInVolume, isWal);
                winnerIsInVolume.set(isInVolume);
            } catch (Throwable thr) {
                TestUtils.assertContains(thr.getMessage(), "[13] table already exists");
                try {
                    assertTableExists(compiler, context, engine.getTableToken(tableName), isInVolume, isWal);
                } catch (Exception unexpected) {
                    throw new RuntimeException(unexpected);
                }
            } finally {
                Path.clearThreadLocals();
                haltLatch.countDown();
            }
        }, threadName);
    }

    private TableToken createPopulateTable(
            CairoConfiguration cairoConfig,
            CairoEngine engine,
            SqlCompiler compiler,
            SqlExecutionContext context,
            String tableName,
            boolean isWal,
            boolean inVolume,
            boolean addIfNotExists
    ) throws Exception {
        StringSink sink = Misc.getThreadLocalBuilder();
        sink.put("CREATE TABLE ");
        if (addIfNotExists) {
            sink.put("IF NOT EXISTS ");
        }
        sink.put(tableName).put('(').put('\n');
        sink.put(" investmentMill LONG,").put('\n');
        sink.put(" ticketThous INT,").put('\n');
        sink.put(" broker SYMBOL INDEX CAPACITY 32,").put('\n');
        sink.put(" ts TIMESTAMP").put('\n');
        sink.put(") TIMESTAMP(ts) PARTITION BY DAY");
        if (isWal) {
            sink.put(" WAL");
        }
        if (inVolume) {
            sink.put(" IN VOLUME '" + otherVolumeAlias + '\'');
        }
        sink.put('\n');
        LOG.info().$("About to execute: ").$(sink).$();
        try (OperationFuture op = compiler.compile(sink.toString(), context).execute(null)) {
            op.await();
        }
        if (isWal) {
            drainWalQueue(engine);
        }

        String insertStmt;
        try (
                TableModel tableModel = new TableModel(cairoConfig, tableName, PartitionBy.DAY)
                        .col("investmentMill", ColumnType.LONG)
                        .col("ticketThous", ColumnType.INT)
                        .col("broker", ColumnType.SYMBOL).symbolCapacity(32)
                        .timestamp("ts")
        ) {
            if (isWal) {
                tableModel.wal();
            }
            insertStmt = insertFromSelectPopulateTableStmt(tableModel, 1000000, firstPartitionName, partitionCount);
        }
        SOCountDownLatch tableWriterReturned = new SOCountDownLatch(1);
        engine.setPoolListener((factoryType, thread, tableToken, event, segment, position) -> {
            LOG.info().$("POOL -> FT: ").$(PoolListener.factoryName(factoryType))
                    .$(", Event: ").$(PoolListener.eventName(event))
                    .$(", TableToken: ").$(tableToken != null ? tableToken.getTableName() : "null")
                    .$();
            if (tableToken != null && tableToken.getTableName().equals(tableName)) {
                if (factoryType == PoolListener.SRC_WRITER && event == PoolListener.EV_RETURN) {
                    LOG.info().$("Releasing writer").$();
                    tableWriterReturned.countDown();
                }
            }
        });
        LOG.info().$("About to execute insert 1000000 rows on table: ").$(tableName).$();
        try (OperationFuture op = compiler.compile(insertStmt, context).execute(null)) {
            op.await();
        }
        if (isWal) {
            drainWalQueue(engine);
        }
        Assert.assertTrue(tableWriterReturned.await(TimeUnit.SECONDS.toNanos(2L)));
        engine.setPoolListener(null);
        LOG.info().$("Inserted 1000000 rows on table: ").$(tableName).$();
        return engine.getTableToken(tableName);
    }
}
