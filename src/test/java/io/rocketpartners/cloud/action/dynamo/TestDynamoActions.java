package io.rocketpartners.cloud.action.dynamo;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import io.rocketpartners.cloud.action.sql.TestSqlActions;
import io.rocketpartners.cloud.model.Api;
import io.rocketpartners.cloud.model.Collection;
import io.rocketpartners.cloud.model.ObjectNode;
import io.rocketpartners.cloud.model.Response;
import io.rocketpartners.cloud.service.Service;
import io.rocketpartners.cloud.service.Service.ServiceListener;
import junit.framework.TestCase;

/**
 * TODO - need to test against a table that does not have a primary sort key
 * 
 * 
 * Patterns we want to test
 * - system should select the right primary or global based on supplied params
 * - have primary and global secondary that share a hash key with different sort keys
 * - have primary and global secondary with different hash keys and same sort key
 * - primary hash + local secondary sort vs global with same hash and different sort
 * - primary hash + local secondary sort vs global with same hash and sort (might be a table design mistake, but what should the system do?)
 * 
 * - can't GetItem on a GSI - https://stackoverflow.com/questions/43732835/getitem-from-secondary-index-with-dynamodb
 * 
 * EQ | NE | LE | LT | GE | GT | NOT_NULL | NULL | CONTAINS | NOT_CONTAINS | BEGINS_WITH | IN | BETWEEN
 * 
 * X = ANY condition
 * 
 * SCENARIO            primary                 gs1                     gs2       
 *                   HK      SK          GS1-HK      GS1-SK      GS2-HK   GS2-SK   LS1   LS2   LS3    FIELD-N        COOOSE
 *   A                                                                                                   X            Scan                need to try to scan on a non indexed field
 *   B                                                                               X                                Scan
 *   C                =                                                                                               Query - PRIMARY
 *   D                =       =                                                                                       GetItem - PRIMARY
 *   E                =       >                                                                                       Query - PRIMARY
 *   F                =       >                                                      >                                Query - PRIMARY
 *   G                =       sw                                                                                      Query - PRIMARY
 *   H                =       sw                                                     =                                Query - LS1
 *   I                =       sw          =               =                                                           Query - GS1
 *   J                =       sw          =               sw         =        =                                       Query - GS2
 *   K                gt      =                                                                                       Scan - Primary
 *   L                gt      sw          =                                                                           ????                                             
 * 
 * 
 * 
 * Access Patters for Order Table
 * 
 *   `OrderID` INTEGER NOT NULL AUTO_INCREMENT,
 *   `CustomerID` VARCHAR(5),
 *   `EmployeeID` INTEGER,
 *   `OrderDate` DATETIME,
 *   `RequiredDate` DATETIME,
 *   `ShippedDate` DATETIME,
 *   `ShipVia` INTEGER,
 *   `Freight` DECIMAL(10,4) DEFAULT 0,
 *   `ShipName` VARCHAR(40),
 *   `ShipAddress` VARCHAR(60),
 *   `ShipCity` VARCHAR(15),
 *   `ShipRegion` VARCHAR(15),
 *   `ShipPostalCode` VARCHAR(10),
 *   `ShipCountry` VARCHAR(15),
 * 
 * LS1 - ShipCity
 * 
 * ORDERS
 * Find an order by id                           HK: OrderID           SK: type       ----  12345   | 'ORDER'
 * Find all orders for a given customer       GS1HK: CustomerID     GS1SK: OrderDate  ----  99999   | '2013-01-08'
 * UNUSED - List orders by date -                HK: type              SK: OrderDate  ----  'ORDER' | '2013-01-08'
 * UNUSED - List orders by employee              HK: employeeId        SK: 
 * 
 * SCENARIO
 *   A - eq(ShipPostalCode, 30305) 
 *   B - 
 *   C - eq(OrderId, 12345)
 *   D - eq(OrderId, 12345)&eq(type, 'ORDER')
 *   E - eq(OrderId, 12345)&gt(type, 'AAAAA')
 *   F - eq(OrderId, 12345)&gt(type, 'AAAAA')&eq(ShipCity,Atlanta)
 *   G - eq(OrderId, 12345)&sw(type, 'ORD')
 *   H - eq(OrderId, 12345)&sw(type, 'ORD')&eq(ShipCity,Atlanta)
 *   I - eq(OrderId, 12345)&sw(type, 'ORD')&eq(CustomerId,9999)&eq(OrderDate,'2013-01-08')
 *   J - ????
 *   K - gt(OrderId, 12345)&eq(type, 'ORDER")
 *   L - ???
 *   
 *   
 *   TODO - need to get projections into indexes so you don't return empty attributes that look null
 */
public class TestDynamoActions extends TestCase
{

   //   public static void main(String[] args) throws Exception
   //   {
   //      TestDynamoActions tests = new TestDynamoActions();
   //      tests.test1();
   //   }

   static Map<String, Service> services = new HashMap();

   // public static void
   //
   public static synchronized Service service(String apiCode, String ddl, String dynamoTbl)
   {
      Service service = services.get(apiCode);
      if (service != null)
         return service;

      service = TestSqlActions.service(apiCode, ddl);

      final Api api = service.getApi(apiCode);
      final DynamoDb dynamoDb = new DynamoDb("dynamo", dynamoTbl);
      api.withDb(dynamoDb);

      service.withListener(new ServiceListener()
         {
            @Override
            public void onStartup(Service service)
            {
               Collection orders = api.getCollection(dynamoTbl + "s");//new Collection(dynamoDb.getTable(dynamoTbl));
               orders.withName("orders");

               orders.getAttribute("hk").withName("orderId"); //get orders by id 
               orders.getAttribute("sk").withName("type");

               orders.getAttribute("gs1hk").withName("employeeId"); //get orders by customer sorted by date
               orders.getAttribute("gs1sk").withName("orderDate");

               orders.getAttribute("ls1").withName("shipCity");
               orders.getAttribute("ls2").withName("shipName");
               orders.getAttribute("ls3").withName("requireDate");

               //orders.getAttribute("gs2hk").setName("customerId"); //get orders by customer sorted by date
               //orders.getAttribute("gs2sk").setName("orderDate");//will be "order"

               orders.withIncludePaths("dynamodb/*");

               api.withCollection(orders);
               api.withEndpoint("GET,PUT,POST,DELETE", "dynamodb", "*").withAction(new DynamoDbRestAction<>());

               //uncomment below to populate db

               //               Response res = service.service("GET", "northwind/sql/orders?or(eq(shipname, 'Blauer See Delikatessen'),eq(customerid,HILAA))&pageSize=100&sort=-orderid");
               //               Node json = res.getJson();
               //               System.out.println(json);
               //
               //               //      res = service.get("northwind/sql/orders").pageSize(100).order("orderid").go();
               //               //      json = res.getJson();
               //               //      System.out.println(json);
               //               assertEquals(json.find("meta.pageSize"), 100);
               //               assertEquals(json.find("meta.rowCount"), 25);
               //               assertEquals(json.find("data.0.orderid"), 11058);
               //
               //               for (Object o : json.getArray("data"))
               //               {
               //                  Node js = (Node) o;
               //                  js.put("type", "ORDER");
               //                  if (service.post("northwind/dynamodb/orders", js).getStatusCode() != 200)
               //                     fail();
               //               }
            }

         });

      services.put(apiCode, service);

      return service;
   }

//   @Test
//   public void testA() throws Exception
//   {
//      Service service = service("northwind", "northwind", "test-northwind");
//      Response res = null;
//      ObjectNode json = null;
//
//      res = service.get("northwind/dynamodb/orders?shipname=Blauer See Delikatessen");
//      json = res.getJson();
//      //System.out.println(res.getDebug());
//
//      assertEquals(7, json.getArray("data").length());
//      assertDebug(res, "ScanSpec", "filterExpression='ls2 = :var1' valueMap={:var1=Blauer See Delikatessen}");
//   }

//   @Test
//   public void testC() throws Exception
//   {
//      Service service = service("northwind", "northwind", "test-northwind");
//      Response res = null;
//      ObjectNode json = null;
//
//      res = service.service("GET", "northwind/dynamodb/orders?orderid=11058");
//      json = res.getJson();
//      System.out.println(res.getDebug());
//
//      assertEquals(json.getArray("data").length(), 1);
//      assertDebug(res, "Index=Primary Index", "QuerySpec", "keyConditionExpression='hk = :var1' valueMap={:var1=11058}");
//   }

   @Test
   public void testD() throws Exception
   {
      Service service = service("northwind", "northwind", "test-northwind");
      Response res = null;
      ObjectNode json = null;

      res = service.get("northwind/dynamodb/orders?orderid=11058&type=ORDER");
      json = res.getJson();
      System.out.println(res.getDebug());

      assertEquals(json.getArray("data").length(), 1);
      assertDebug(res, "Index=Primary Index", "GetItemSpec", "partKeyCol=hk partKeyVal=11058 sortKeyCol=sk sortKeyVal=ORDER");

      res = service.get("northwind/dynamodb/orders/11058~ORDER");
      json = res.getJson();
      System.out.println(res.getDebug());

      assertEquals(json.getArray("data").length(), 1);
      assertDebug(res, "Index=Primary Index", "GetItemSpec", "partKeyCol=hk partKeyVal=11058 sortKeyCol=sk sortKeyVal=ORDER");
   }

   @Test
   public void testE() throws Exception
   {
      Service service = service("northwind", "northwind", "test-northwind");
      Response res = null;
      ObjectNode json = null;

      res = service.get("northwind/dynamodb/orders?eq(OrderId, 11058)&gt(type, 'AAAAA')");
      json = res.getJson();
      System.out.println(res.getDebug());

      assertEquals(json.getArray("data").length(), 1);
      assertDebug(res, "Index=Primary Index", "QuerySpec", "keyConditionExpression='hk = :var1 and sk > :var2' valueMap={:var1=11058, :var2=AAAAA}");
   }

   @Test
   public void testF() throws Exception
   {
      Service service = service("northwind", "northwind", "test-northwind");
      Response res = null;
      ObjectNode json = null;

      res = service.get("northwind/dynamodb/orders?eq(OrderId, 12345)&gt(type, 'AAAAA')&gt(ShipCity,A)");
      json = res.getJson();
      System.out.println(res.getDebug());

      assertDebug(res, "Index=Primary Index", "keyConditionExpression='hk = :var1 and sk > :var2' filterExpression='ls1 > :var3' valueMap={:var1=12345, :var2=AAAAA, :var3=A}");
   }

   @Test
   public void testG() throws Exception
   {
      Service service = service("northwind", "northwind", "test-northwind");
      Response res = null;
      ObjectNode json = null;

      res = service.get("northwind/dynamodb/orders?eq(OrderId, 11058)&sw(type, 'ORD')");
      json = res.getJson();
      System.out.println(res.getDebug());

      assertEquals(json.getArray("data").length(), 1);
      assertDebug(res, "Index=Primary Index", "QuerySpec", "keyConditionExpression='hk = :var1 and begins_with(sk,:var2)' valueMap={:var1=11058, :var2=ORD}");
   }

   @Test
   public void testH() throws Exception
   {
      Service service = service("northwind", "northwind", "test-northwind");
      Response res = null;
      ObjectNode json = null;

      res = service.get("northwind/dynamodb/orders?eq(OrderId, 11058)&sw(type, 'ORD')&eq(shipcity,Mannheim)");
      //res = service.get("northwind/dynamodb/orders?eq(OrderId, 11058)&eq(shipcity,Mannheim)");
      //res = service.get("northwind/dynamodb/orders?eq(shipcity,Mannheim)");
      json = res.getJson();
      System.out.println(res.getDebug());

      assertEquals(json.getArray("data").length(), 1);
      assertDebug(res, "Index=ls1", "QuerySpec", "keyConditionExpression='hk = :var1 and ls1 = :var2' filterExpression='begins_with(sk,:var3)' valueMap={:var1=11058, :var2=Mannheim, :var3=ORD}");
   }

   @Test
   public void testI() throws Exception
   {
      Service service = service("northwind", "northwind", "test-northwind");
      Response res = null;
      ObjectNode json = null;

      res = service.get("northwind/dynamodb/orders?eq(OrderId, 11058)&sw(type, 'ORD')&eq(EmployeeId,9)&eq(OrderDate,'2014-10-29T00:00-0400')");
      json = res.getJson();
      System.out.println(res.getDebug());

      assertEquals(json.getArray("data").length(), 1);
      assertDebug(res, "Index=gs1", "QuerySpec", "keyConditionExpression='gs1hk = :var1 and gs1sk = :var2' filterExpression='begins_with(sk,:var3) and hk = :var4' valueMap={:var1=9, :var2=2014-10-29T00:00-0400, :var3=ORD, :var4=11058}");
   }

   @Test
   public void testK() throws Exception
   {
      Service service = service("northwind", "northwind", "test-northwind");
      Response res = null;
      ObjectNode json = null;

      res = service.get("northwind/dynamodb/orders?gt(OrderId, 1)&eq(type, ORDER)");
      json = res.getJson();
      System.out.println(res.getDebug());

      //assertEquals(json.getArray("data").length(), 1);
      assertDebug(res, "DynamoDb ScanSpec -> maxPageSize=100 filterExpression='sk = :var1 and hk > :var2' valueMap={:var1=ORDER, :var2=1}");
   }

   void assertDebug(Response resp, String... matches)
   {
      for (String match : matches)
         if (resp.getDebug().indexOf(match) < 0)
            fail("missing debug match: " + match);
   }
}
