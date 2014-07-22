package com.inmobi.grill.server.query;

/*
 * #%L
 * Grill Server
 * %%
 * Copyright (C) 2014 Inmobi
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import com.inmobi.grill.api.APIResult;
import com.inmobi.grill.api.GrillConf;
import com.inmobi.grill.api.GrillException;
import com.inmobi.grill.api.GrillSessionHandle;
import com.inmobi.grill.api.query.*;
import com.inmobi.grill.api.query.QueryStatus.Status;
import com.inmobi.grill.driver.hive.HiveDriver;
import com.inmobi.grill.driver.hive.TestHiveDriver.FailHook;
import com.inmobi.grill.driver.hive.TestRemoteHiveDriver;
import com.inmobi.grill.server.GrillJerseyTest;
import com.inmobi.grill.server.GrillServices;
import com.inmobi.grill.server.api.GrillConfConstants;
import com.inmobi.grill.server.api.driver.GrillDriver;
import com.inmobi.grill.server.api.metrics.MetricsService;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.io.IOUtils;
import org.apache.hive.service.Service;
import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.media.multipart.FormDataBodyPart;
import org.glassfish.jersey.media.multipart.FormDataContentDisposition;
import org.glassfish.jersey.media.multipart.FormDataMultiPart;
import org.glassfish.jersey.media.multipart.MultiPartFeature;
import org.testng.Assert;
import org.testng.annotations.*;

import javax.ws.rs.BadRequestException;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import static org.testng.Assert.*;

@Test(groups="unit-test", suiteName = "queryServiceUnitTests")
public class TestQueryService extends GrillJerseyTest {
  public static final Log LOG = LogFactory.getLog(TestQueryService.class);
  QueryExecutionServiceImpl queryService;
  MetricsService metricsSvc;
  GrillSessionHandle grillSessionId;
  final int NROWS = 10000;
  boolean fileCreated;

  @BeforeTest
  public void setUpServices() throws Exception {
    super.setUp();
    queryService = (QueryExecutionServiceImpl)GrillServices.get().getService("query");
    metricsSvc = (MetricsService)GrillServices.get().getService(MetricsService.NAME);
    grillSessionId = queryService.openSession("foo", "bar", new HashMap<String, String>());
  }

  @AfterTest
  public void tearDownServices() throws Exception {
    queryService.closeSession(grillSessionId);
    for (GrillDriver driver : queryService.getDrivers()) {
      if (driver instanceof HiveDriver) {
        assertFalse(((HiveDriver) driver).hasGrillSession(grillSessionId));
      }
    }
    super.tearDown();
  }

  @BeforeClass
  public void createTables() throws InterruptedException {
    createTable(testTable);
    loadData(testTable, TEST_DATA_FILE);
  }

  @AfterClass
  public void dropTables() throws InterruptedException {
    dropTable(testTable);
  }

  @Override
  protected Application configure() {
    return new QueryApp();
  }

  @Override
  protected void configureClient(ClientConfig config) {
    config.register(MultiPartFeature.class);
  }

  protected static String testTable = "TEST_TABLE";
  public static final String TEST_DATA_FILE = "../grill-driver-hive/testdata/testdata2.txt";

  private void createTable(String tblName) throws InterruptedException {
    createTable(tblName, target(), grillSessionId);
  }

  void createTable(String tblName, WebTarget parent,
      GrillSessionHandle grillSessionId) throws InterruptedException {
    GrillConf conf = new GrillConf();
    conf.addProperty(GrillConfConstants.QUERY_PERSISTENT_RESULT_INDRIVER, "false");
    final WebTarget target = parent.path("queryapi/queries");

    final FormDataMultiPart mp = new FormDataMultiPart();
    String createTable = "CREATE TABLE IF NOT EXISTS " + tblName  +"(ID INT, IDSTR VARCHAR(100))";

    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("sessionid").build(),
        grillSessionId, MediaType.APPLICATION_XML_TYPE));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("query").build(),
        createTable));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("operation").build(),
        "execute"));
    mp.bodyPart(new FormDataBodyPart(
        FormDataContentDisposition.name("conf").fileName("conf").build(),
        conf,
        MediaType.APPLICATION_XML_TYPE));

    final QueryHandle handle = target.request().post(
        Entity.entity(mp, MediaType.MULTIPART_FORM_DATA_TYPE), QueryHandle.class);

    // wait till the query finishes
    GrillQuery ctx = target.path(handle.toString()).queryParam("sessionid", grillSessionId).request().get(GrillQuery.class);
    QueryStatus stat = ctx.getStatus();
    while (!stat.isFinished()) {
      ctx = target.path(handle.toString()).queryParam("sessionid", grillSessionId).request().get(GrillQuery.class);
      stat = ctx.getStatus();
      Thread.sleep(1000);
    }
    assertTrue(ctx.getSubmissionTime() > 0);
    assertTrue(ctx.getLaunchTime() > 0);
    assertTrue(ctx.getDriverStartTime() > 0);
    assertTrue(ctx.getDriverFinishTime() > 0);
    assertTrue(ctx.getFinishTime() > 0);
    Assert.assertEquals(ctx.getStatus().getStatus(), QueryStatus.Status.SUCCESSFUL);
  }

  void loadData(String tblName, final String TEST_DATA_FILE)
      throws InterruptedException {
    loadData(tblName, TEST_DATA_FILE, target(), grillSessionId);
  }

  void loadData(String tblName, final String TEST_DATA_FILE,
      WebTarget parent, GrillSessionHandle grillSessionId) throws InterruptedException {
    GrillConf conf = new GrillConf();
    conf.addProperty(GrillConfConstants.QUERY_PERSISTENT_RESULT_INDRIVER, "false");
    final WebTarget target = parent.path("queryapi/queries");

    final FormDataMultiPart mp = new FormDataMultiPart();
    String dataLoad = "LOAD DATA LOCAL INPATH '"+ TEST_DATA_FILE +
        "' OVERWRITE INTO TABLE " + tblName;

    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("sessionid").build(),
        grillSessionId, MediaType.APPLICATION_XML_TYPE));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("query").build(),
        dataLoad));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("operation").build(),
        "execute"));
    mp.bodyPart(new FormDataBodyPart(
        FormDataContentDisposition.name("conf").fileName("conf").build(),
        conf,
        MediaType.APPLICATION_XML_TYPE));

    final QueryHandle handle = target.request().post(
        Entity.entity(mp, MediaType.MULTIPART_FORM_DATA_TYPE), QueryHandle.class);

    // wait till the query finishes
    GrillQuery ctx = target.path(handle.toString()).queryParam("sessionid", grillSessionId).request().get(GrillQuery.class);
    QueryStatus stat = ctx.getStatus();
    while (!stat.isFinished()) {
      ctx = target.path(handle.toString()).queryParam("sessionid", grillSessionId).request().get(GrillQuery.class);
      stat = ctx.getStatus();
      Thread.sleep(1000);
    }
    Assert.assertEquals(ctx.getStatus().getStatus(), QueryStatus.Status.SUCCESSFUL);

  }

  private void dropTable(String tblName) throws InterruptedException {
    dropTable(tblName, target(), grillSessionId);
  }

  void dropTable(String tblName, WebTarget parent,
      GrillSessionHandle grillSessionId) throws InterruptedException {
    GrillConf conf = new GrillConf();
    conf.addProperty(GrillConfConstants.QUERY_PERSISTENT_RESULT_INDRIVER, "false");
    final WebTarget target = parent.path("queryapi/queries");

    final FormDataMultiPart mp = new FormDataMultiPart();
    String createTable = "DROP TABLE IF EXISTS " + tblName ;

    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("sessionid").build(),
        grillSessionId, MediaType.APPLICATION_XML_TYPE));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("query").build(),
        createTable));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("operation").build(),
        "execute"));
    mp.bodyPart(new FormDataBodyPart(
        FormDataContentDisposition.name("conf").fileName("conf").build(),
        conf,
        MediaType.APPLICATION_XML_TYPE));

    final QueryHandle handle = target.request().post(
        Entity.entity(mp, MediaType.MULTIPART_FORM_DATA_TYPE), QueryHandle.class);

    // wait till the query finishes
    GrillQuery ctx = target.path(handle.toString()).queryParam("sessionid", grillSessionId).request().get(GrillQuery.class);
    QueryStatus stat = ctx.getStatus();
    while (!stat.isFinished()) {
      ctx = target.path(handle.toString()).queryParam("sessionid", grillSessionId).request().get(GrillQuery.class);
      stat = ctx.getStatus();
      Thread.sleep(1000);
    }
    Assert.assertEquals(ctx.getStatus().getStatus(), QueryStatus.Status.SUCCESSFUL);
  }

  // test get a random query, should return 400
  @Test(groups = "unit" )
  public void testGetRandomQuery() {
    final WebTarget target = target().path("queryapi/queries");

    Response rs = target.path("random").queryParam("sessionid", grillSessionId).request().get();
    Assert.assertEquals(rs.getStatus(), 400);
  }

  @Test(groups = "unit" )
  public void testLaunchFail() throws InterruptedException {
    final WebTarget target = target().path("queryapi/queries");
    long failedQueries = metricsSvc.getTotalFailedQueries();
    System.out.println("%% " + failedQueries);
    GrillConf conf = new GrillConf();
    final FormDataMultiPart mp = new FormDataMultiPart();
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("sessionid").build(),
        grillSessionId, MediaType.APPLICATION_XML_TYPE));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("query")
        .build(),
        "select ID from non_exist_table"));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name(
        "operation").build(),
        "execute"));
    mp.bodyPart(new FormDataBodyPart(
        FormDataContentDisposition.name("conf").fileName("conf").build(),
        conf,
        MediaType.APPLICATION_XML_TYPE));
    final QueryHandle handle = target.request().post(
        Entity.entity(mp, MediaType.MULTIPART_FORM_DATA_TYPE), QueryHandle.class);

    Assert.assertNotNull(handle);
    
    GrillQuery ctx = target.path(handle.toString()).queryParam("sessionid", grillSessionId).request().get(GrillQuery.class);
    QueryStatus stat = ctx.getStatus();
    while (!stat.isFinished()) {
      ctx = target.path(handle.toString()).queryParam("sessionid", grillSessionId).request().get(GrillQuery.class);
      stat = ctx.getStatus();
      System.out.println("%% query " + ctx.getQueryHandle() + " status:" + stat);
      Thread.sleep(1000);
    }
    
    assertTrue(ctx.getSubmissionTime() > 0);
    assertEquals(ctx.getLaunchTime(), 0);
    assertEquals(ctx.getDriverStartTime(), 0);
    assertEquals(ctx.getDriverFinishTime(), 0);
    assertTrue(ctx.getFinishTime() > 0);
    Assert.assertEquals(ctx.getStatus().getStatus(), QueryStatus.Status.FAILED);
    System.out.println("%% " + metricsSvc.getTotalFailedQueries());
    Assert.assertEquals(metricsSvc.getTotalFailedQueries(), failedQueries + 1);
  }

  // test with execute async post, get all queries, get query context,
  // get wrong uuid query
  @Test(groups = "unit" )
  public void testQueriesAPI() throws InterruptedException {
    // test post execute op
    final WebTarget target = target().path("queryapi/queries");
    GrillConf conf = new GrillConf();
    conf.addProperty("hive.exec.driver.run.hooks", FailHook.class.getCanonicalName());
    final FormDataMultiPart mp = new FormDataMultiPart();
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("sessionid").build(),
        grillSessionId, MediaType.APPLICATION_XML_TYPE));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("query")
        .build(),
        "select ID from " + testTable));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name(
        "operation").build(),
        "execute"));
    mp.bodyPart(new FormDataBodyPart(
        FormDataContentDisposition.name("conf").fileName("conf").build(),
        conf,
        MediaType.APPLICATION_XML_TYPE));
    
    long queuedQueries = metricsSvc.getQueuedQueries();
    long runningQueries = metricsSvc.getRunningQueries();
    long finishedQueries = metricsSvc.getFinishedQueries();
    
    final QueryHandle handle = target.request().post(
        Entity.entity(mp, MediaType.MULTIPART_FORM_DATA_TYPE), QueryHandle.class);

    Assert.assertNotNull(handle);

    // Get all queries
    // XML 
    List<QueryHandle> allQueriesXML = target.queryParam("sessionid", grillSessionId).request(MediaType.APPLICATION_XML)
        .get(new GenericType<List<QueryHandle>>() {
        });
    Assert.assertTrue(allQueriesXML.size() >= 1);

    //JSON
    //  List<QueryHandle> allQueriesJSON = target.request(
    //      MediaType.APPLICATION_JSON).get(new GenericType<List<QueryHandle>>() {
    //  });
    //  Assert.assertEquals(allQueriesJSON.size(), 1);
    //JAXB
    List<QueryHandle> allQueries = (List<QueryHandle>)target.queryParam("sessionid", grillSessionId).request().get(
        new GenericType<List<QueryHandle>>(){});
    Assert.assertTrue(allQueries.size() >= 1);
    Assert.assertTrue(allQueries.contains(handle));

    // Get query
    // Invocation.Builder builderjson = target.path(handle.toString()).request(MediaType.APPLICATION_JSON);
    // String responseJSON = builderjson.get(String.class);
    // System.out.println("query JSON:" + responseJSON);
    String queryXML = target.path(handle.toString()).queryParam("sessionid", grillSessionId).request(MediaType.APPLICATION_XML).get(String.class);
    System.out.println("query XML:" + queryXML);

    Response response = target.path(handle.toString() + "001").queryParam("sessionid", grillSessionId).request().get();
    Assert.assertEquals(response.getStatus(), 404);

    GrillQuery ctx = target.path(handle.toString()).queryParam("sessionid", grillSessionId).request().get(GrillQuery.class);
    // Assert.assertEquals(ctx.getStatus().getStatus(), QueryStatus.Status.QUEUED);

    // wait till the query finishes
    QueryStatus stat = ctx.getStatus();
    while (!stat.isFinished()) {
      ctx = target.path(handle.toString()).queryParam("sessionid", grillSessionId).request().get(GrillQuery.class);
      stat = ctx.getStatus();
      switch (stat.getStatus()) {
      case RUNNING:
        assertEquals(metricsSvc.getRunningQueries(), runningQueries + 1);
        break;
      case QUEUED:
        assertEquals(metricsSvc.getQueuedQueries(), queuedQueries + 1);
        break;
      default: // nothing
      }
      Thread.sleep(1000);
    }
    assertTrue(ctx.getSubmissionTime() > 0);
    assertTrue(ctx.getLaunchTime() > 0);
    assertTrue(ctx.getDriverStartTime() > 0);
    assertTrue(ctx.getDriverFinishTime() > 0);
    assertTrue(ctx.getFinishTime() > 0);
    //Assert.assertEquals(ctx.getStatus().getStatus(), QueryStatus.Status.FAILED);
    assertEquals(metricsSvc.getFinishedQueries(), finishedQueries + 1);
    
    // Update conf for query
    final FormDataMultiPart confpart = new FormDataMultiPart();
    conf = new GrillConf();
    conf.addProperty("my.property", "myvalue");
    confpart.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("sessionid").build(),
        grillSessionId, MediaType.APPLICATION_XML_TYPE));
    confpart.bodyPart(new FormDataBodyPart(
        FormDataContentDisposition.name("conf").fileName("conf").build(),
        conf,
        MediaType.APPLICATION_XML_TYPE));
    APIResult updateConf = target.path(handle.toString()).request().put(
        Entity.entity(confpart, MediaType.MULTIPART_FORM_DATA_TYPE),
        APIResult.class);
    Assert.assertEquals(updateConf.getStatus(), APIResult.Status.FAILED);
  }


  @Test(groups = "unit" )
  public void testExecuteWithoutSessionId() throws Exception {
    // test post execute op
    final WebTarget target = target().path("queryapi/queries");
    GrillConf conf = new GrillConf();
    conf.addProperty("hive.exec.driver.run.hooks", FailHook.class.getCanonicalName());
    final FormDataMultiPart mp = new FormDataMultiPart();

    /**
     * We are not passing session id in this test
     */
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("query")
      .build(),
      "select ID from " + testTable));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name(
      "operation").build(),
      "execute"));
    mp.bodyPart(new FormDataBodyPart(
      FormDataContentDisposition.name("conf").fileName("conf").build(),
      conf,
      MediaType.APPLICATION_XML_TYPE));

    try {
      final QueryHandle handle = target.request().post(
        Entity.entity(mp, MediaType.MULTIPART_FORM_DATA_TYPE), QueryHandle.class);
      Assert.fail("Should have thrown bad request error");
    } catch (BadRequestException badReqeust) {
      // pass
    }
  }

  // Test explain query
  @Test(groups = "unit")
  public void testExplainQuery() throws InterruptedException {    
    final WebTarget target = target().path("queryapi/queries");

    final FormDataMultiPart mp = new FormDataMultiPart();
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("sessionid").build(),
        grillSessionId, MediaType.APPLICATION_XML_TYPE));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("query").build(),
        "select ID from " + testTable));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("operation").build(),
        "explain"));
    mp.bodyPart(new FormDataBodyPart(
        FormDataContentDisposition.name("conf").fileName("conf").build(),
        new GrillConf(),
        MediaType.APPLICATION_XML_TYPE));

    final QueryPlan plan = target.request().post(
        Entity.entity(mp, MediaType.MULTIPART_FORM_DATA_TYPE), QueryPlan.class);
    Assert.assertEquals(plan.getNumSels(), 1);
    Assert.assertEquals(plan.getTablesQueried().size(), 1);
    Assert.assertTrue(plan.getTablesQueried().get(0).equalsIgnoreCase(testTable));
    Assert.assertNull(plan.getPrepareHandle());
  }

  // post to preparedqueries
  // get all prepared queries
  // get a prepared query
  // update a prepared query
  // post to prepared query multiple times
  // delete a prepared query
  @Test(groups = "unit" )
  public void testPrepareQuery() throws InterruptedException {    
    final WebTarget target = target().path("queryapi/preparedqueries");

    final FormDataMultiPart mp = new FormDataMultiPart();
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("sessionid").build(),
        grillSessionId, MediaType.APPLICATION_XML_TYPE));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("query").build(),
        "select ID from " + testTable));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("operation").build(),
        "prepare"));
    mp.bodyPart(new FormDataBodyPart(
        FormDataContentDisposition.name("conf").fileName("conf").build(),
        new GrillConf(),
        MediaType.APPLICATION_XML_TYPE));

    final QueryPrepareHandle pHandle = target.request().post(
        Entity.entity(mp, MediaType.MULTIPART_FORM_DATA_TYPE), QueryPrepareHandle.class);

    // Get all prepared queries
    List<QueryPrepareHandle> allQueries = (List<QueryPrepareHandle>)target
        .queryParam("sessionid", grillSessionId).request().get(new GenericType<List<QueryPrepareHandle>>(){});
    Assert.assertTrue(allQueries.size() >= 1);
    Assert.assertTrue(allQueries.contains(pHandle));

    GrillPreparedQuery ctx = target.path(pHandle.toString()).queryParam("sessionid", grillSessionId).request().get(
        GrillPreparedQuery.class);
    Assert.assertTrue(ctx.getUserQuery().equalsIgnoreCase(
        "select ID from " + testTable));
    Assert.assertTrue(ctx.getDriverQuery().equalsIgnoreCase(
        "select ID from " + testTable));
    //Assert.assertEquals(ctx.getSelectedDriverClassName(),
    //    com.inmobi.grill.driver.hive.HiveDriver.class.getCanonicalName());
    Assert.assertNull(ctx.getConf().getProperties().get("my.property"));

    // Update conf for prepared query
    final FormDataMultiPart confpart = new FormDataMultiPart();
    GrillConf conf = new GrillConf();
    conf.addProperty("my.property", "myvalue");
    confpart.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("sessionid").build(),
        grillSessionId, MediaType.APPLICATION_XML_TYPE));
    confpart.bodyPart(new FormDataBodyPart(
        FormDataContentDisposition.name("conf").fileName("conf").build(),
        conf,
        MediaType.APPLICATION_XML_TYPE));
    APIResult updateConf = target.path(pHandle.toString()).request().put(
        Entity.entity(confpart, MediaType.MULTIPART_FORM_DATA_TYPE),
        APIResult.class);
    Assert.assertEquals(updateConf.getStatus(), APIResult.Status.SUCCEEDED);

    ctx = target.path(pHandle.toString()).queryParam("sessionid", grillSessionId).request().get(
        GrillPreparedQuery.class);
    Assert.assertEquals(ctx.getConf().getProperties().get("my.property"),
        "myvalue");

    QueryHandle handle1 = target.path(pHandle.toString()).request().post(
        Entity.entity(confpart, MediaType.MULTIPART_FORM_DATA_TYPE),
        QueryHandle.class);

    // do post once again
    QueryHandle handle2 = target.path(pHandle.toString()).request().post(
        Entity.entity(confpart, MediaType.MULTIPART_FORM_DATA_TYPE),
        QueryHandle.class);
    Assert.assertNotEquals(handle1, handle2);

    GrillQuery ctx1 = target().path("queryapi/queries").path(
        handle1.toString()).queryParam("sessionid", grillSessionId).request().get(GrillQuery.class);
    // wait till the query finishes
    QueryStatus stat = ctx1.getStatus();
    while (!stat.isFinished()) {
      ctx1 = target().path("queryapi/queries").path(
          handle1.toString()).queryParam("sessionid", grillSessionId).request().get(GrillQuery.class);
      stat = ctx1.getStatus();
      Thread.sleep(1000);
    }
    Assert.assertEquals(ctx1.getStatus().getStatus(), QueryStatus.Status.SUCCESSFUL);

    GrillQuery ctx2 = target().path("queryapi/queries").path(
        handle2.toString()).queryParam("sessionid", grillSessionId).request().get(GrillQuery.class);
    // wait till the query finishes
    stat = ctx2.getStatus();
    while (!stat.isFinished()) {
      ctx2 = target().path("queryapi/queries").path(
          handle1.toString()).queryParam("sessionid", grillSessionId).request().get(GrillQuery.class);
      stat = ctx2.getStatus();
      Thread.sleep(1000);
    }
    Assert.assertEquals(ctx1.getStatus().getStatus(), QueryStatus.Status.SUCCESSFUL);

    // destroy prepared
    APIResult result = target.path(pHandle.toString()).queryParam("sessionid", grillSessionId).request().delete(APIResult.class);
    Assert.assertEquals(result.getStatus(), APIResult.Status.SUCCEEDED);

    // Post on destroyed query
    Response response = target.path(pHandle.toString()).request().post(
        Entity.entity(confpart, MediaType.MULTIPART_FORM_DATA_TYPE),
        Response.class);
    Assert.assertEquals(response.getStatus(), 404);
  }

  @Test(groups = "unit" )
  public void testExplainAndPrepareQuery() throws InterruptedException {    
    final WebTarget target = target().path("queryapi/preparedqueries");

    final FormDataMultiPart mp = new FormDataMultiPart();
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("sessionid").build(),
        grillSessionId, MediaType.APPLICATION_XML_TYPE));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("query").build(),
        "select ID from " + testTable));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("operation").build(),
        "explain_and_prepare"));
    mp.bodyPart(new FormDataBodyPart(
        FormDataContentDisposition.name("conf").fileName("conf").build(),
        new GrillConf(),
        MediaType.APPLICATION_XML_TYPE));

    final QueryPlan plan = target.request().post(
        Entity.entity(mp, MediaType.MULTIPART_FORM_DATA_TYPE), QueryPlan.class);
    Assert.assertEquals(plan.getNumSels(), 1);
    Assert.assertEquals(plan.getTablesQueried().size(), 1);
    Assert.assertTrue(plan.getTablesQueried().get(0).equalsIgnoreCase(testTable));
    Assert.assertNotNull(plan.getPrepareHandle());

    GrillPreparedQuery ctx = target.path(plan.getPrepareHandle().toString())
        .queryParam("sessionid", grillSessionId).request().get(GrillPreparedQuery.class);
    Assert.assertTrue(ctx.getUserQuery().equalsIgnoreCase(
        "select ID from " + testTable));
    Assert.assertTrue(ctx.getDriverQuery().equalsIgnoreCase(
        "select ID from " + testTable));
    Assert.assertEquals(ctx.getSelectedDriverClassName(),
        com.inmobi.grill.driver.hive.HiveDriver.class.getCanonicalName());
    Assert.assertNull(ctx.getConf().getProperties().get("my.property"));

    // Update conf for prepared query
    final FormDataMultiPart confpart = new FormDataMultiPart();
    GrillConf conf = new GrillConf();
    conf.addProperty("my.property", "myvalue");
    confpart.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("sessionid").build(),
        grillSessionId, MediaType.APPLICATION_XML_TYPE));
    confpart.bodyPart(new FormDataBodyPart(
        FormDataContentDisposition.name("conf").fileName("conf").build(),
        conf,
        MediaType.APPLICATION_XML_TYPE));
    APIResult updateConf = target.path(plan.getPrepareHandle().toString()).request().put(
        Entity.entity(confpart, MediaType.MULTIPART_FORM_DATA_TYPE),
        APIResult.class);
    Assert.assertEquals(updateConf.getStatus(), APIResult.Status.SUCCEEDED);

    ctx = target.path(plan.getPrepareHandle().toString()).queryParam("sessionid", grillSessionId).request().get(
        GrillPreparedQuery.class);
    Assert.assertEquals(ctx.getConf().getProperties().get("my.property"),
        "myvalue");

    QueryHandle handle1 = target.path(plan.getPrepareHandle().toString()).request().post(
        Entity.entity(confpart, MediaType.MULTIPART_FORM_DATA_TYPE),
        QueryHandle.class);

    // do post once again
    QueryHandle handle2 = target.path(plan.getPrepareHandle().toString()).request().post(
        Entity.entity(confpart, MediaType.MULTIPART_FORM_DATA_TYPE),
        QueryHandle.class);
    Assert.assertNotEquals(handle1, handle2);

    GrillQuery ctx1 = target().path("queryapi/queries").path(
        handle1.toString()).queryParam("sessionid", grillSessionId).request().get(GrillQuery.class);
    // wait till the query finishes
    QueryStatus stat = ctx1.getStatus();
    while (!stat.isFinished()) {
      ctx1 = target().path("queryapi/queries").path(
          handle1.toString()).queryParam("sessionid", grillSessionId).request().get(GrillQuery.class);
      stat = ctx1.getStatus();
      Thread.sleep(1000);
    }
    Assert.assertEquals(ctx1.getStatus().getStatus(), QueryStatus.Status.SUCCESSFUL);

    GrillQuery ctx2 = target().path("queryapi/queries").path(
        handle2.toString()).queryParam("sessionid", grillSessionId).request().get(GrillQuery.class);
    // wait till the query finishes
    stat = ctx2.getStatus();
    while (!stat.isFinished()) {
      ctx2 = target().path("queryapi/queries").path(
          handle1.toString()).queryParam("sessionid", grillSessionId).request().get(GrillQuery.class);
      stat = ctx2.getStatus();
      Thread.sleep(1000);
    }
    Assert.assertEquals(ctx1.getStatus().getStatus(), QueryStatus.Status.SUCCESSFUL);

    // destroy prepared
    APIResult result = target.path(plan.getPrepareHandle().toString())
        .queryParam("sessionid", grillSessionId).request().delete(APIResult.class);
    Assert.assertEquals(result.getStatus(), APIResult.Status.SUCCEEDED);

    // Post on destroyed query
    Response response = target.path(plan.getPrepareHandle().toString())
        .request().post(
            Entity.entity(confpart, MediaType.MULTIPART_FORM_DATA_TYPE),
            Response.class);
    Assert.assertEquals(response.getStatus(), 404);

  }

  // test with execute async post, get query, get results
  // test cancel query
  @Test(groups = "unit" )
  public void testExecuteAsync() throws InterruptedException, IOException {
    System.out.println("TEST_EXECUTE_ASYNC");
    // test post execute op
    final WebTarget target = target().path("queryapi/queries");
    
    long queuedQueries = metricsSvc.getQueuedQueries();
    long runningQueries = metricsSvc.getRunningQueries();
    
    final FormDataMultiPart mp = new FormDataMultiPart();
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("sessionid").build(),
        grillSessionId, MediaType.APPLICATION_XML_TYPE));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("query").build(),
        "select ID, IDSTR from " + testTable));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name(
        "operation").build(),
        "execute"));
    mp.bodyPart(new FormDataBodyPart(
        FormDataContentDisposition.name("conf").fileName("conf").build(),
        new GrillConf(),
        MediaType.APPLICATION_XML_TYPE));
    final QueryHandle handle = target.request().post(
        Entity.entity(mp, MediaType.MULTIPART_FORM_DATA_TYPE), QueryHandle.class);

    Assert.assertNotNull(handle);

    // Get query
    GrillQuery ctx = target.path(handle.toString()).queryParam("sessionid", grillSessionId).request().get(GrillQuery.class);
    Assert.assertTrue(ctx.getStatus().getStatus().equals(Status.QUEUED) ||
        ctx.getStatus().getStatus().equals(Status.LAUNCHED) ||
        ctx.getStatus().getStatus().equals(Status.RUNNING) ||
        ctx.getStatus().getStatus().equals(Status.SUCCESSFUL));

    // wait till the query finishes
    QueryStatus stat = ctx.getStatus();
    while (!stat.isFinished()) {
      ctx = target.path(handle.toString()).queryParam("sessionid", grillSessionId).request().get(GrillQuery.class);
      stat = ctx.getStatus();
      switch (stat.getStatus()) {
      case RUNNING:
        assertEquals(metricsSvc.getRunningQueries(), runningQueries + 1, "Asserting queries for " + ctx.getQueryHandle());
        break;
      case QUEUED:
        assertEquals(metricsSvc.getQueuedQueries(), queuedQueries + 1);
        break;
      default: // nothing
      }
      Thread.sleep(1000);
    }
    assertTrue(ctx.getSubmissionTime() > 0);
    assertTrue(ctx.getLaunchTime() > 0);
    assertTrue(ctx.getDriverStartTime() > 0);
    assertTrue(ctx.getDriverFinishTime() > 0);
    assertTrue(ctx.getFinishTime() > 0);
    Assert.assertEquals(ctx.getStatus().getStatus(), QueryStatus.Status.SUCCESSFUL);
    
    validatePersistedResult(handle, target(), grillSessionId, true);

    // test cancel query
    final QueryHandle handle2 = target.request().post(
        Entity.entity(mp, MediaType.MULTIPART_FORM_DATA_TYPE), QueryHandle.class);

    Assert.assertNotNull(handle2);
    APIResult result = target.path(handle2.toString())
        .queryParam("sessionid", grillSessionId).request().delete(APIResult.class);
    // cancel would fail query is already successful
    Assert.assertTrue(result.getStatus().equals(APIResult.Status.SUCCEEDED) ||
        result.getStatus().equals(APIResult.Status.FAILED));

    GrillQuery ctx2 = target.path(handle2.toString()).queryParam("sessionid",
        grillSessionId).request().get(GrillQuery.class);
    if (result.getStatus().equals(APIResult.Status.FAILED)) {
      Assert.assertTrue(ctx2.getStatus().getStatus() == QueryStatus.Status.SUCCESSFUL);
    } else {
      Assert.assertTrue(ctx2.getStatus().getStatus() == QueryStatus.Status.CANCELED);
    }
  }

  void validatePersistedResult(QueryHandle handle, WebTarget parent,
      GrillSessionHandle grillSessionId, boolean isDir) throws IOException {
    final WebTarget target = parent.path("queryapi/queries");
    // fetch results
    validateResultSetMetadata(handle, parent, grillSessionId);

    String presultset = target.path(handle.toString()).path(
        "resultset").queryParam("sessionid", grillSessionId).request().get(String.class);
    System.out.println("PERSISTED RESULT:" + presultset);

    PersistentQueryResult resultset = target.path(handle.toString()).path(
        "resultset").queryParam("sessionid", grillSessionId).request().get(PersistentQueryResult.class);
    validatePersistentResult(resultset, handle, isDir);

    if (isDir) {
      validNotFoundForHttpResult(parent, grillSessionId, handle);
    }
  }

  List<String> readResultSet(PersistentQueryResult resultset, QueryHandle handle, boolean isDir) throws  IOException {
    Assert.assertTrue(resultset.getPersistedURI().contains(handle.toString()));
    Path actualPath = new Path(resultset.getPersistedURI());
    FileSystem fs = actualPath.getFileSystem(new Configuration());
    List<String> actualRows = new ArrayList<String>();
    if (fs.getFileStatus(actualPath).isDir()) {
      Assert.assertTrue(isDir);
      for (FileStatus fstat : fs.listStatus(actualPath)) {
        addRowsFromFile(actualRows, fs, fstat.getPath());
      }
    } else {
      Assert.assertFalse(isDir);
      addRowsFromFile(actualRows, fs, actualPath);
    }
    return actualRows;
  }

  void addRowsFromFile(List<String> actualRows,
      FileSystem fs, Path path) throws IOException {
    FSDataInputStream in = fs.open(path);
    BufferedReader br = null;
    try {
      br = new BufferedReader(new InputStreamReader(in));
      String line = "";

      while ((line = br.readLine()) != null) {
        actualRows.add(line);
      }
    } finally {
      if (br != null) {
        br.close();
      }
      if (in != null) {
        in.close();
      }
    }
  }

  void validatePersistentResult(QueryResult resultset,
      QueryHandle handle, boolean isDir) throws IOException {
    List<String> actualRows = readResultSet((PersistentQueryResult)resultset, handle, isDir);
    Assert.assertEquals(actualRows.get(0), "1one");
    Assert.assertEquals(actualRows.get(1), "\\Ntwo");
    Assert.assertEquals(actualRows.get(2), "3\\N");
    Assert.assertEquals(actualRows.get(3), "\\N\\N");
    Assert.assertEquals(actualRows.get(4), "5");
  }

  void validateHttpEndPoint(WebTarget parent,
      GrillSessionHandle grillSessionId,
      QueryHandle handle, String redirectUrl) throws IOException {
    Response response = parent.path(
        "queryapi/queries/" +handle.toString() + "/httpresultset")
        .queryParam("sessionid", grillSessionId).request().get();

    Assert.assertTrue(response.getHeaderString("content-disposition").contains(handle.toString()));

    if (redirectUrl == null) {
      InputStream in = (InputStream)response.getEntity();
      ByteArrayOutputStream bos = new ByteArrayOutputStream();
      IOUtils.copyBytes(in, bos, new Configuration());
      bos.close();
      in.close();

      String result = new String(bos.toByteArray());
      List<String> actualRows = Arrays.asList(result.split("\n"));
      Assert.assertEquals(actualRows.get(0), "1one");
      Assert.assertEquals(actualRows.get(1), "\\Ntwo");
      Assert.assertEquals(actualRows.get(2), "3\\N");
      Assert.assertEquals(actualRows.get(3), "\\N\\N");
      Assert.assertEquals(actualRows.get(4), "5");
    } else {
      Assert.assertEquals(Response.Status.SEE_OTHER.getStatusCode(), response.getStatus());
      Assert.assertTrue(response.getHeaderString("Location").contains(redirectUrl));
    }
  }

  void validNotFoundForHttpResult(WebTarget parent, GrillSessionHandle grillSessionId,
      QueryHandle handle) {
    try {
      Response response = parent.path(
          "queryapi/queries/" +handle.toString() + "/httpresultset")
          .queryParam("sessionid", grillSessionId).request().get();
      if (Response.Status.NOT_FOUND.getStatusCode() != response.getStatus()) {
        Assert.fail("Expected not found excepiton, but got:" + response.getStatus());
      }
      Assert.assertEquals(response.getStatus(), Response.Status.NOT_FOUND.getStatusCode());
    } catch (NotFoundException e) {
      // expected
    }

  }
  // test with execute async post, get query, get results
  // test cancel query
  @Test(groups = "unit" )
  public void testExecuteAsyncInMemoryResult() throws InterruptedException, IOException {
    // test post execute op
    final WebTarget target = target().path("queryapi/queries");

    final FormDataMultiPart mp = new FormDataMultiPart();
    GrillConf conf = new GrillConf();
    conf.addProperty(GrillConfConstants.QUERY_PERSISTENT_RESULT_INDRIVER, "false");
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("sessionid").build(),
        grillSessionId, MediaType.APPLICATION_XML_TYPE));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("query").build(),
        "select ID, IDSTR from " + testTable));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name(
        "operation").build(),
        "execute"));
    mp.bodyPart(new FormDataBodyPart(
        FormDataContentDisposition.name("conf").fileName("conf").build(),
        conf,
        MediaType.APPLICATION_XML_TYPE));
    final QueryHandle handle = target.request().post(
        Entity.entity(mp, MediaType.MULTIPART_FORM_DATA_TYPE), QueryHandle.class);

    Assert.assertNotNull(handle);

    // Get query
    GrillQuery ctx = target.path(handle.toString()).queryParam("sessionid", grillSessionId).request().get(GrillQuery.class);
    Assert.assertTrue(ctx.getStatus().getStatus().equals(Status.QUEUED) ||
        ctx.getStatus().getStatus().equals(Status.LAUNCHED) ||
        ctx.getStatus().getStatus().equals(Status.RUNNING) ||
        ctx.getStatus().getStatus().equals(Status.SUCCESSFUL));

    // wait till the query finishes
    QueryStatus stat = ctx.getStatus();
    while (!stat.isFinished()) {
      ctx = target.path(handle.toString()).queryParam("sessionid", grillSessionId).request().get(GrillQuery.class);
      stat = ctx.getStatus();
      Thread.sleep(1000);
    }
    Assert.assertEquals(ctx.getStatus().getStatus(), QueryStatus.Status.SUCCESSFUL);

    // fetch results
    validateResultSetMetadata(handle, target(), grillSessionId);

    InMemoryQueryResult resultset = target.path(handle.toString()).path(
        "resultset").queryParam("sessionid", grillSessionId).request().get(InMemoryQueryResult.class);
    validateInmemoryResult(resultset);

    validNotFoundForHttpResult(target(), grillSessionId, handle);
  }

  @Test(groups = "unit" )
  public void testExecuteAsyncTempTable() throws InterruptedException, IOException {
    // test post execute op
    final WebTarget target = target().path("queryapi/queries");

    final FormDataMultiPart mp = new FormDataMultiPart();
    GrillConf conf = new GrillConf();
    conf.addProperty(GrillConfConstants.QUERY_PERSISTENT_RESULT_INDRIVER, "false");
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("sessionid").build(),
        grillSessionId, MediaType.APPLICATION_XML_TYPE));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("query").build(),
        "create table temp_output as select ID, IDSTR from " + testTable));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name(
        "operation").build(),
        "execute"));
    mp.bodyPart(new FormDataBodyPart(
        FormDataContentDisposition.name("conf").fileName("conf").build(),
        conf,
        MediaType.APPLICATION_XML_TYPE));
    final QueryHandle handle = target.request().post(
        Entity.entity(mp, MediaType.MULTIPART_FORM_DATA_TYPE), QueryHandle.class);

    Assert.assertNotNull(handle);

    // Get query
    GrillQuery ctx = target.path(handle.toString()).queryParam("sessionid", grillSessionId).request().get(GrillQuery.class);
    Assert.assertTrue(ctx.getStatus().getStatus().equals(Status.QUEUED) ||
        ctx.getStatus().getStatus().equals(Status.LAUNCHED) ||
        ctx.getStatus().getStatus().equals(Status.RUNNING) ||
        ctx.getStatus().getStatus().equals(Status.SUCCESSFUL));

    // wait till the query finishes
    QueryStatus stat = ctx.getStatus();
    while (!stat.isFinished()) {
      ctx = target.path(handle.toString()).queryParam("sessionid", grillSessionId).request().get(GrillQuery.class);
      stat = ctx.getStatus();
      Thread.sleep(1000);
    }
    Assert.assertEquals(ctx.getStatus().getStatus(), QueryStatus.Status.SUCCESSFUL);

    String select = "SELECT * FROM temp_output";
    final FormDataMultiPart fetch = new FormDataMultiPart();
    fetch.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("sessionid").build(),
        grillSessionId, MediaType.APPLICATION_XML_TYPE));
    fetch.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("query").build(),
        select));
    fetch.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name(
        "operation").build(),
        "execute"));
    fetch.bodyPart(new FormDataBodyPart(
        FormDataContentDisposition.name("conf").fileName("conf").build(),
        conf,
        MediaType.APPLICATION_XML_TYPE));
    final QueryHandle handle2 = target.request().post(
        Entity.entity(fetch, MediaType.MULTIPART_FORM_DATA_TYPE), QueryHandle.class);

    Assert.assertNotNull(handle2);

    // Get query
    ctx = target.path(handle2.toString()).queryParam("sessionid", grillSessionId).request().get(GrillQuery.class);

    // wait till the query finishes
    stat = ctx.getStatus();
    while (!stat.isFinished()) {
      ctx = target.path(handle2.toString()).queryParam("sessionid", grillSessionId).request().get(GrillQuery.class);
      stat = ctx.getStatus();
      Thread.sleep(1000);
    }
    Assert.assertEquals(ctx.getStatus().getStatus(), QueryStatus.Status.SUCCESSFUL);

    // fetch results
    validateResultSetMetadata(handle2, "temp_output.", target(), grillSessionId);

    InMemoryQueryResult resultset = target.path(handle2.toString()).path(
        "resultset").queryParam("sessionid", grillSessionId).request().get(InMemoryQueryResult.class);
    validateInmemoryResult(resultset);
  }

  void validateResultSetMetadata(QueryHandle handle, WebTarget parent,
      GrillSessionHandle grillSessionId) {
    validateResultSetMetadata(handle, "", parent, grillSessionId);
  }

  void validateResultSetMetadata(QueryHandle handle,
      String outputTablePfx, WebTarget parent,
      GrillSessionHandle grillSessionId) {
    final WebTarget target = parent.path("queryapi/queries");

    QueryResultSetMetadata metadata = target.path(handle.toString()).path(
        "resultsetmetadata").queryParam("sessionid", grillSessionId).request().get(QueryResultSetMetadata.class);
    Assert.assertEquals(metadata.getColumns().size(), 2);
    assertTrue(metadata.getColumns().get(0).getName().toLowerCase().equals((outputTablePfx + "ID").toLowerCase()) ||
        metadata.getColumns().get(0).getName().toLowerCase().equals("ID".toLowerCase()));
    assertEquals(metadata.getColumns().get(0).getType().name().toLowerCase(), "INT".toLowerCase());
    assertTrue(metadata.getColumns().get(1).getName().toLowerCase().equals((outputTablePfx + "IDSTR").toLowerCase()) ||
        metadata.getColumns().get(0).getName().toLowerCase().equals("IDSTR".toLowerCase()));
    assertEquals(metadata.getColumns().get(1).getType().name().toLowerCase(), "VARCHAR".toLowerCase());
  }

  void validateInmemoryResult(InMemoryQueryResult resultset) {
    Assert.assertEquals(resultset.getRows().size(), 5);
    Assert.assertEquals(resultset.getRows().get(0).getValues().get(0), 1);
    Assert.assertEquals((String)resultset.getRows().get(0).getValues().get(1), "one");

    Assert.assertNull(resultset.getRows().get(1).getValues().get(0));
    Assert.assertEquals((String)resultset.getRows().get(1).getValues().get(1), "two");

    Assert.assertEquals(resultset.getRows().get(2).getValues().get(0), 3);
    Assert.assertNull(resultset.getRows().get(2).getValues().get(1));

    Assert.assertNull(resultset.getRows().get(3).getValues().get(0));
    Assert.assertNull(resultset.getRows().get(3).getValues().get(1));
    Assert.assertEquals(resultset.getRows().get(4).getValues().get(0), 5);
    Assert.assertEquals(resultset.getRows().get(4).getValues().get(1), "");    
  }

  // test execute with timeout, fetch results
  // cancel the query with execute_with_timeout
  @Test(groups = "unit" )
  public void testExecuteWithTimeoutQuery() throws IOException {
    final WebTarget target = target().path("queryapi/queries");

    final FormDataMultiPart mp = new FormDataMultiPart();
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("sessionid").build(),
        grillSessionId, MediaType.APPLICATION_XML_TYPE));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("query").build(),
        "select ID, IDSTR from " + testTable));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("operation").build(),
        "execute_with_timeout"));
    mp.bodyPart(new FormDataBodyPart(
        FormDataContentDisposition.name("conf").fileName("conf").build(),
        new GrillConf(),
        MediaType.APPLICATION_XML_TYPE));

    QueryHandleWithResultSet result = target.request().post(
        Entity.entity(mp, MediaType.MULTIPART_FORM_DATA_TYPE), QueryHandleWithResultSet.class);
    Assert.assertNotNull(result.getQueryHandle());
    Assert.assertNotNull(result.getResult());
    validatePersistentResult(result.getResult(), result.getQueryHandle(), true);
    
    final FormDataMultiPart mp2 = new FormDataMultiPart();
    GrillConf conf = new GrillConf();
    conf.addProperty(GrillConfConstants.QUERY_PERSISTENT_RESULT_INDRIVER, "false");
    mp2.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("sessionid").build(),
        grillSessionId, MediaType.APPLICATION_XML_TYPE));
    mp2.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("query").build(),
        "select ID, IDSTR from " + testTable));
    mp2.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name(
        "operation").build(),
        "execute_with_timeout"));
    mp2.bodyPart(new FormDataBodyPart(
        FormDataContentDisposition.name("conf").fileName("conf").build(),
        conf,
        MediaType.APPLICATION_XML_TYPE));

    result = target.request().post(
        Entity.entity(mp2, MediaType.MULTIPART_FORM_DATA_TYPE), QueryHandleWithResultSet.class);
    Assert.assertNotNull(result.getQueryHandle());
    Assert.assertNotNull(result.getResult());
    validateInmemoryResult((InMemoryQueryResult) result.getResult());

  }

  private void createRestartTestDataFile() throws FileNotFoundException {
    if (fileCreated) {
      return;
    }

    File dataFile = new File("target/testdata.txt");
    dataFile.deleteOnExit();

    PrintWriter dataFileOut = new PrintWriter(dataFile);
    for (int i = 0; i < NROWS; i++) {
      dataFileOut.println(i);
    }
    dataFileOut.flush();
    dataFileOut.close();
    fileCreated = true;
  }

  @Test(groups = "query-server-restart", dependsOnGroups = "unit")
  public void testGrillServerRestart() throws InterruptedException, IOException, GrillException {
    LOG.info("Server restart test");

    // Create data file
    createRestartTestDataFile();

    // Create a test table
    createTable("test_server_restart");
    loadData("test_server_restart", "target/testdata.txt");
    LOG.info("Loaded data");

    // test post execute op
    final WebTarget target = target().path("queryapi/queries");

    List<QueryHandle> launchedQueries = new ArrayList<QueryHandle>();
    final int NUM_QUERIES = 10;

    boolean killed = false;
    for (int i = 0; i < NUM_QUERIES; i++) {
      if (!killed && i > NUM_QUERIES / 3) {
        // Kill the query submitter thread to make sure some queries stay in accepted queue
        try {
          queryService.querySubmitter.interrupt();
          LOG.info("Stopped query submitter");
        } catch (Exception exc) {
          LOG.error("Could not kill query submitter", exc);
        }
        killed = true;
      }

      final FormDataMultiPart mp = new FormDataMultiPart();
      mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("sessionid").build(),
          grillSessionId, MediaType.APPLICATION_XML_TYPE));
      mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("query").build(),
        "select COUNT(ID) from test_server_restart"));
      mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name(
         "operation").build(),
        "execute"
      ));
      mp.bodyPart(new FormDataBodyPart(
          FormDataContentDisposition.name("conf").fileName("conf").build(),
          new GrillConf(),
          MediaType.APPLICATION_XML_TYPE));
      final QueryHandle handle = target.request().post(
          Entity.entity(mp, MediaType.MULTIPART_FORM_DATA_TYPE), QueryHandle.class);

      Assert.assertNotNull(handle);
      GrillQuery ctx = target.path(handle.toString())
        .queryParam("sessionid", grillSessionId).request().get(GrillQuery.class);
      QueryStatus stat = ctx.getStatus();
      LOG.info(i + " submitted query " + handle + " state: " + ctx.getStatus().getStatus());
      launchedQueries.add(handle);
    }

    // Restart the server
    LOG.info("Restarting grill server!");
    HiveConf conf = new HiveConf();
    conf.setIntVar(HiveConf.ConfVars.HIVE_SERVER2_THRIFT_CLIENT_CONNECTION_RETRY_LIMIT, 3);
    conf.setIntVar(HiveConf.ConfVars.HIVE_SERVER2_THRIFT_CLIENT_RETRY_LIMIT, 3);
    conf.setIntVar(HiveConf.ConfVars.HIVE_SERVER2_THRIFT_CLIENT_RETRY_DELAY_SECONDS, 10);
    restartGrillServer(conf);
    queryService = (QueryExecutionServiceImpl)GrillServices.get().getService("query");

    // All queries should complete after server restart
    for (QueryHandle handle : launchedQueries) {
      LOG.info("Polling query " + handle);
      try {
        GrillQuery ctx = target.path(handle.toString())
          .queryParam("sessionid", grillSessionId).request().get(GrillQuery.class);
        QueryStatus stat = ctx.getStatus();
        while (!stat.isFinished()) {
          LOG.info("Polling query " + handle + " Status:" + stat);
          ctx = target.path(handle.toString()).queryParam("sessionid", grillSessionId).request().get(GrillQuery.class);
          stat = ctx.getStatus();
          Thread.sleep(1000);
        }
        Assert.assertEquals(ctx.getStatus().getStatus(), QueryStatus.Status.SUCCESSFUL,
          "Expected to be successful " + handle);
        PersistentQueryResult resultset = target.path(handle.toString()).path(
          "resultset").queryParam("sessionid", grillSessionId).request().get(PersistentQueryResult.class);
        List<String> rows = readResultSet(resultset, handle, true);
        Assert.assertEquals(rows.size(), 1);
        Assert.assertEquals(rows.get(0), "" + NROWS);
        LOG.info("Completed " + handle);
      } catch (Exception exc) {
        LOG.error("Failed query "  + handle, exc);
        Assert.fail(exc.getMessage());
      }
    }
    LOG.info("End server restart test");
  }

  @Test(groups = "hive-server-restart", dependsOnGroups = "query-server-restart")
  public void testHiveServerRestart() throws Exception {
    // Create data file
    createRestartTestDataFile();
    // Create a test table
    createTable("test_hive_server_restart");
    loadData("test_hive_server_restart", "target/testdata.txt");
    LOG.info("Loaded data");

    LOG.info("Hive Server restart test");
    // test post execute op
    final WebTarget target = target().path("queryapi/queries");

    // Submit query, restart HS2, submit another query
    FormDataMultiPart mp = new FormDataMultiPart();
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("sessionid").build(),
      grillSessionId, MediaType.APPLICATION_XML_TYPE));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("query").build(),
      "select COUNT(ID) from test_hive_server_restart"));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name(
      "operation").build(),
      "execute"
    ));
    mp.bodyPart(new FormDataBodyPart(
      FormDataContentDisposition.name("conf").fileName("conf").build(),
      new GrillConf(),
      MediaType.APPLICATION_XML_TYPE));
    QueryHandle handle = target.request().post(
      Entity.entity(mp, MediaType.MULTIPART_FORM_DATA_TYPE), QueryHandle.class);

    Assert.assertNotNull(handle);

    // Restart hive server
    TestRemoteHiveDriver.stopHS2Service();

    // Wait for server to stop
    while (TestRemoteHiveDriver.server.getServiceState() != Service.STATE.STOPPED) {
      LOG.info("Waiting for HS2 to stop. Current state " + TestRemoteHiveDriver.server.getServiceState());
      Thread.sleep(1000);
    }

    TestRemoteHiveDriver.createHS2Service();
    // Wait for server to come up
    while (Service.STATE.STARTED != TestRemoteHiveDriver.server.getServiceState()) {
      LOG.info("Waiting for HS2 to start " + TestRemoteHiveDriver.server.getServiceState());
      Thread.sleep(1000);
    }
    Thread.sleep(10000);
    LOG.info("Server restarted");

    // Poll for first query, we should not get any exception
    GrillQuery ctx = target.path(handle.toString())
      .queryParam("sessionid", grillSessionId).request().get(GrillQuery.class);
    QueryStatus stat = ctx.getStatus();
    while (!stat.isFinished()) {
      LOG.info("Polling query " + handle + " Status:" + stat);
      ctx = target.path(handle.toString()).queryParam("sessionid", grillSessionId).request().get(GrillQuery.class);
      stat = ctx.getStatus();
      Thread.sleep(1000);
    }

    Assert.assertTrue(stat.isFinished());
    LOG.info("Previous query status: " + stat.getStatusMessage());

    for (int i = 0; i < 5; i++) {
      // Submit another query, again no exception expected
      mp = new FormDataMultiPart();
      mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("sessionid").build(),
        grillSessionId, MediaType.APPLICATION_XML_TYPE));
      mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("query").build(),
        "select COUNT(ID) from test_hive_server_restart"));
      mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name(
        "operation").build(),
        "execute"
      ));
      mp.bodyPart(new FormDataBodyPart(
        FormDataContentDisposition.name("conf").fileName("conf").build(),
        new GrillConf(),
        MediaType.APPLICATION_XML_TYPE));
      handle = target.request().post(
        Entity.entity(mp, MediaType.MULTIPART_FORM_DATA_TYPE), QueryHandle.class);
      Assert.assertNotNull(handle);

      // Poll for second query, this should finish successfully
      ctx = target.path(handle.toString())
        .queryParam("sessionid", grillSessionId).request().get(GrillQuery.class);
      stat = ctx.getStatus();
      while (!stat.isFinished()) {
        LOG.info("Post restart polling query " + handle + " Status:" + stat);
        ctx = target.path(handle.toString()).queryParam("sessionid", grillSessionId).request().get(GrillQuery.class);
        stat = ctx.getStatus();
        Thread.sleep(1000);
      }
      LOG.info("@@ "+ i + " Final status for " + handle + " " + stat.getStatus());
    }

    //Assert.assertEquals(stat.getStatus(), QueryStatus.Status.SUCCESSFUL,
    //    "Expected to be successful " + handle);

    LOG.info("End hive server restart test");
  }

  @Override
  protected int getTestPort() {
    return 8083;
  }
}
