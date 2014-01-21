package com.inmobi.yoda.cube.ddl;


import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.metastore.api.Database;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.ql.cube.metadata.*;
import org.apache.hadoop.hive.ql.io.HiveIgnoreKeyTextOutputFormat;
import org.apache.hadoop.hive.ql.metadata.Hive;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hadoop.hive.ql.session.SessionState;
import org.apache.hadoop.hive.serde.serdeConstants;
import org.apache.hadoop.mapred.TextInputFormat;
import org.testng.annotations.AfterTest;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeTest;
import org.testng.annotations.Test;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.testng.Assert.*;

public class TestCubeSelectorService {
  public static final String TEST_DB = "test_cube_selector_db";
  private DimensionDDL dimDDL;
  private HiveConf conf;
  private CubeMetastoreClient metastore;

  private void createTestDim(CubeMetastoreClient client)
    throws HiveException {
    String dimName = "testDim";

    List<FieldSchema>  dimColumns = new ArrayList<FieldSchema>();
    dimColumns.add(new FieldSchema("id", "int", "code"));
    dimColumns.add(new FieldSchema("testdim_name", "string", "field1"));

    Map<String, List<TableReference>> dimensionReferences =
      new HashMap<String, List<TableReference>>();

    Map<String, UpdatePeriod> dumpPeriods = new HashMap<String, UpdatePeriod>();
    ArrayList<FieldSchema> partCols = new ArrayList<FieldSchema>();
    List<String> timePartCols = new ArrayList<String>();
    partCols.add(new FieldSchema("dt",
        serdeConstants.STRING_TYPE_NAME,
        "date partition"));
    timePartCols.add("dt");
    Storage hdfsStorage1 = Storage.createInstance(HDFSStorage.class.getCanonicalName(), "C1");
    StorageTableDesc s1 = new StorageTableDesc();
    s1.setInputFormat(TextInputFormat.class.getCanonicalName());
    s1.setOutputFormat(HiveIgnoreKeyTextOutputFormat.class.getCanonicalName());
    s1.setPartCols(partCols);
    s1.setTimePartCols(timePartCols);
    dumpPeriods.put(hdfsStorage1.getName(), UpdatePeriod.HOURLY);

    Storage hdfsStorage2 = Storage.createInstance(HDFSStorage.class.getCanonicalName(), "C2");
    StorageTableDesc s2 = new StorageTableDesc();
    s2.setInputFormat(TextInputFormat.class.getCanonicalName());
    s2.setOutputFormat(HiveIgnoreKeyTextOutputFormat.class.getCanonicalName());
    dumpPeriods.put(hdfsStorage2.getName(), null);

    Map<Storage, StorageTableDesc> storageTables = new HashMap<Storage, StorageTableDesc>();
    storageTables.put(hdfsStorage1, s1);
    storageTables.put(hdfsStorage2, s2);

    Map<String, String> dimProps = new HashMap<String, String>();
    dimProps.put(MetastoreConstants.TIMED_DIMENSION, "dt");

    client.createCubeDimensionTable(dimName, dimColumns, 0L,
        dimensionReferences, dumpPeriods, dimProps, storageTables);

  }

  @BeforeTest
  public void setup() throws Exception {
    conf = new HiveConf(TestCubeSelectorService.class);
    SessionState.start(conf);
    Hive client = Hive.get(conf);
    Database database = new Database();
    database.setName(TEST_DB);
    client.createDatabase(database);

    SessionState.get().setCurrentDatabase(TEST_DB);
    metastore = CubeMetastoreClient.getInstance(conf);
    metastore.setCurrentDatabase(TEST_DB);

    dimDDL = new DimensionDDL(conf);
    CubeDDL cubeDDL = new CubeDDL(dimDDL, conf);
    cubeDDL.createAllCubes();
    createTestDim(metastore);
    System.out.println("##setup test cubeselector service");
  }

  @AfterTest
  public void tearDown() throws Exception {
    Hive.get(conf).dropDatabase(TEST_DB, true, true, true);
    System.out.println("##teardown cubeselector service");
  }

  @Test
  public void testSelectCube() throws Exception  {
    String columns[] = {"bl_billedcount", "dl_joined_count"};

    CubeSelectorService selector = CubeSelectorFactory.getSelectorSvcInstance(conf);

    Map<Set<String>, Set<AbstractCubeTable>> selected = selector.select(Arrays.asList(columns));

    assertNotNull(selected);
    assertEquals(selected.size(), 2);

    Cube click = metastore.getCube("cube_billing");
    Cube dlUnMatch = metastore.getCube("cube_downloadunmatch");
    Cube dlMatch = metastore.getCube("cube_downloadmatch");

    System.out.println("## result " + selected.toString());
    Set<AbstractCubeTable> clickList = selected.get(new HashSet<String>(Arrays.asList("bl_billedcount")));
    assertEquals(clickList.size(), 1, "Size mistmatch:" + clickList.toString());
    assertTrue(clickList.contains(click));

    Set<AbstractCubeTable> dlList = selected.get(new HashSet<String>(Arrays.asList("dl_joined_count")));
    assertEquals(dlList.size(), 2);
    assertEquals(dlList,
      new HashSet<AbstractCubeTable>(Arrays.asList(dlUnMatch, dlMatch)));
  }

  @Test
  public void testSelectDimension() throws Exception {
    String columns[] = {"bl_billedcount", "testdim_name"};
    CubeSelectorService selector = CubeSelectorFactory.getSelectorSvcInstance(conf);

    Map<Set<String>, Set<AbstractCubeTable>> selection = selector.select(Arrays.asList(columns));

    assertNotNull(selection);
    assertEquals(selection.size(), 2);

    Cube click = metastore.getCube("cube_billing");
    CubeDimensionTable testDim = metastore.getDimensionTable("testDim");

    Set<AbstractCubeTable> clickList = new HashSet<AbstractCubeTable>();
    clickList.add(click);
    Set<AbstractCubeTable> dimList = new HashSet<AbstractCubeTable>();
    dimList.add(testDim);

    Set<String> clickCols = new HashSet<String>();
    clickCols.add("bl_billedcount");

    Set<String> dimCols = new HashSet<String>();
    dimCols.add("testdim_name");

    assertEquals(selection.get(clickCols), clickList);
    assertEquals(selection.get(dimCols), dimList);
  }

  @Test
  public void testMultiThreadedSelect() throws Exception {
    // Test that sessionstate does not throw an error in each of the threads.
    final AtomicInteger success = new AtomicInteger(0);
    final int NUM_THRS = 10;
    Thread threads[] = new Thread[NUM_THRS];
    for (int i = 0; i < NUM_THRS; i++) {
      threads[i] = new Thread(new Runnable() {
        @Override
        public void run() {
          try {
            CubeSelectorService svc = CubeSelectorFactory.getSelectorSvcInstance(conf, true);
            assertNotNull(svc);
            success.incrementAndGet();
          } catch (Exception e) {
            e.printStackTrace();
          }
        }
      });
      threads[i].start();
    }

    for (Thread th : threads) {
      th.join();
    }
    assertEquals(success.get(), NUM_THRS);
  }

}
