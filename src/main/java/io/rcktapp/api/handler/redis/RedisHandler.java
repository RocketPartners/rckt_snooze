package io.rcktapp.api.handler.redis;

import java.util.Hashtable;
import java.util.Map;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.forty11.web.js.JS;
import io.forty11.web.js.JSObject;
import io.rcktapp.api.Action;
import io.rcktapp.api.Api;
import io.rcktapp.api.Chain;
import io.rcktapp.api.Endpoint;
import io.rcktapp.api.Handler;
import io.rcktapp.api.Request;
import io.rcktapp.api.Response;
import io.rcktapp.api.SC;
import io.rcktapp.api.service.Service;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

/**
 * The service builds a key from the request url & parameters.  If the key does not exist within Redis,
 * the request is passed along to the GetHandler.  The response from the GetHandler will be inserted
 * into Redis with an expiration.
 * 
 * The initial Redis check can be bypassed by including the skipCache (verify value below) request parameter. 
 * 
 * The current implementation of Jedis.set() does not allow clobbering a key/value & expiration but will in 
 * a future build. Because of that, Jedis.setex() is used.  Since the SET command options can replace SETNX, 
 * SETEX, PSETEX, it is possible that in future versions of Redis these three commands will be deprecated 
 * and finally removed.
 * 
 * Jedis.set() parameter explanation...
 * nxxx NX|XX, NX -- Only set the key if it does not already exist. XX -- Only set the key if it already exist.
 * expx EX|PX, expire time units: EX = seconds; PX = milliseconds
 
 * A future version of jedis alter's .set() to allow for a SetParams object to be used to set 'ex'
 * without requiring the setting of 'nx' 
 * 
 * @author kfrankic
 *
 */
public class RedisHandler implements Handler
{
   Logger                       log                                = LoggerFactory.getLogger(getClass());

   // configurable snooze.props 
   protected String             redisHost                          = null;
   protected int                redisPort                          = 6379;

   protected int                redisPoolMin                       = 16;
   protected int                redisPoolMax                       = 128;
   protected boolean            redisTestOnBorrow                  = true;
   protected boolean            redisTestOnReturn                  = true;
   protected boolean            redisTestWhileIdle                 = true;
   protected int                redisMinEvictableIdleTimeMillis    = 60000;
   protected int                redisTimeBetweenEvictionRunsMillis = 30000;
   protected int                redisNumTestsPerEvictionRun        = 3;
   protected boolean            redisBlockWhenExhausted            = true;

   protected String             redisNocacheParam                  = "nocache";
   protected int                redisReadSocketTimeout             = 2500;                               // time in milliseconds
   protected int                redisTtl                           = 15552000;                           // time to live 15,552,000s == 180 days

   Hashtable<String, JedisPool> pools                              = new Hashtable();

   @Override
   public void service(Service service, Api api, Endpoint endpoint, Action action, Chain chain, Request req, Response res) throws Exception
   {
      //caching only makes sense for GET requests
      if (!"GET".equalsIgnoreCase(req.getMethod()))
         return;

      //only cache top level request, on internal recursive requests
      if (chain.getParent() != null)
         return;

      String nocacheParam = chain.getConfig("redisNocacheParam", this.redisNocacheParam);

      // remove this param before creating the key, so this param is not included in the key
      boolean skipCache = (req.removeParam(nocacheParam) != null);

      if (skipCache)
         return;

      Jedis jedis = null;

      try
      {
         if (!skipCache)
         {
            // the key is derived from the URL
            String key = getCacheKey(chain);

            // request should include a json object
            JSObject resJson = null;

            String value = null;
            try
            {
               jedis = getPool(chain).getResource();
               value = jedis.get(key);
            }
            catch (Exception ex)
            {
               log.warn("Failed to retrieve from Redis the key: " + key, ex);
            }

            if (value != null)
            {
               log.debug("CACHE HIT : " + key);

               resJson = JS.toJSObject(value);
               res.setJson(resJson);
               res.setStatus(SC.SC_200_OK);
               chain.cancel();
            }
            else
            {
               log.debug("CACHE MISS: " + key);

               chain.go();

               // TODO should the naming convention include the TTL in the name?

               // see class header for explanation on setex()  
               // jedis.set(key, chain.getResponse().getJson().toString(), setParams().ex(ttl));

               if (res.getStatusCode() == 200)
               {
                  try
                  {
                     int ttl = chain.getConfig("redisTtl", this.redisTtl);
                     jedis.setex(key, ttl, chain.getResponse().getJson().toString());
                  }
                  catch (Exception ex)
                  {
                     log.warn("Failed to save Redis key: " + key, ex);
                  }
               }
            }
         }
      }
      finally
      {
         if (jedis != null)
         {
            try
            {
               jedis.close();
            }
            catch (Exception ex)
            {
               log.warn("Error closing redis connection", ex);
            }
         }
      }
   }

   /**
    * Sorts the request parameters alphabetically
    * @param requestParamMap map representing the request parameters
    * @return a concatenated string of each param beginning with '?' and joined by '&'
    */
   String getCacheKey(Chain chain)
   {
      TreeMap<String, String> sortedKeyMap = new TreeMap<>(chain.getRequest().getParams());

      String sortedParams = "";

      boolean isFirstParam = true;

      for (Map.Entry<String, String> entry : sortedKeyMap.entrySet())
      {
         if (isFirstParam)
         {
            sortedParams += "?";
            isFirstParam = false;
         }
         else
            sortedParams += "&";

         sortedParams += entry.getKey();

         if (!entry.getValue().isEmpty())
         {
            sortedParams += "=" + entry.getValue();
         }

      }

      String key = chain.getRequest().getApiUrl();
      key = key.substring(key.indexOf("://") + 3);
      key += chain.getRequest().getPath();
      key += sortedParams;

      return key;
   }

   JedisPool getPool(Chain chain)
   {
      String host = chain.getConfig("redisHost", this.redisHost);
      int port = chain.getConfig("redisPort", this.redisPort);

      String poolKey = chain.getConfig("redisPoolKey", host + ":" + port);

      JedisPool jedis = pools.get(poolKey);
      if (jedis == null)
      {
         synchronized (this)
         {
            jedis = pools.get(poolKey);
            if (jedis == null)
            {
               JedisPoolConfig poolConfig = new JedisPoolConfig();
               poolConfig.setMaxTotal(chain.getConfig("redisPoolMax", this.redisPoolMax));
               poolConfig.setMaxIdle(chain.getConfig("redisPoolMax", this.redisPoolMax));
               poolConfig.setMinIdle(chain.getConfig("redisPoolMin", this.redisPoolMin));
               poolConfig.setTestOnBorrow(chain.getConfig("redisTestOnBorrow", this.redisTestOnBorrow));
               poolConfig.setTestOnReturn(chain.getConfig("redisTestOnReturn", this.redisTestOnReturn));
               poolConfig.setTestWhileIdle(chain.getConfig("redisTestWhileIdle", this.redisTestWhileIdle));
               poolConfig.setMinEvictableIdleTimeMillis(chain.getConfig("redisMinEvictableIdleTimeMillis", this.redisMinEvictableIdleTimeMillis));
               poolConfig.setTimeBetweenEvictionRunsMillis(chain.getConfig("redisTimeBetweenEvictionRunsMillis", this.redisTimeBetweenEvictionRunsMillis));
               poolConfig.setNumTestsPerEvictionRun(chain.getConfig("redisNumTestsPerEvictionRun", this.redisNumTestsPerEvictionRun));
               poolConfig.setBlockWhenExhausted(chain.getConfig("redisBlockWhenExhausted", this.redisBlockWhenExhausted));

               jedis = new JedisPool(poolConfig, host, port, chain.getConfig("redisReadSocketTimeout", this.redisReadSocketTimeout));
               pools.put(poolKey, jedis);
            }
         }
      }

      return jedis;
   }

   public void setRedisHost(String redisHost)
   {
      this.redisHost = redisHost;
   }

   public void setRedisPort(int redisPort)
   {
      this.redisPort = redisPort;
   }

   public void setRedisPoolMin(int redisPoolMin)
   {
      this.redisPoolMin = redisPoolMin;
   }

   public void setRedisPoolMax(int redisPoolMax)
   {
      this.redisPoolMax = redisPoolMax;
   }

   public void setRedisTestOnBorrow(boolean redisTestOnBorrow)
   {
      this.redisTestOnBorrow = redisTestOnBorrow;
   }

   public void setRedisTestOnReturn(boolean redisTestOnReturn)
   {
      this.redisTestOnReturn = redisTestOnReturn;
   }

   public void setRedisTestWhileIdle(boolean redisTestWhileIdle)
   {
      this.redisTestWhileIdle = redisTestWhileIdle;
   }

   public void setRedisMinEvictableIdleTimeMillis(int redisMinEvictableIdleTimeMillis)
   {
      this.redisMinEvictableIdleTimeMillis = redisMinEvictableIdleTimeMillis;
   }

   public void setRedisTimeBetweenEvictionRunsMillis(int redisTimeBetweenEvictionRunsMillis)
   {
      this.redisTimeBetweenEvictionRunsMillis = redisTimeBetweenEvictionRunsMillis;
   }

   public void setRedisNumTestsPerEvictionRun(int redisNumTestsPerEvictionRun)
   {
      this.redisNumTestsPerEvictionRun = redisNumTestsPerEvictionRun;
   }

   public void setRedisBlockWhenExhausted(boolean redisBlockWhenExhausted)
   {
      this.redisBlockWhenExhausted = redisBlockWhenExhausted;
   }

   public void setRedisNocacheParam(String redisNocacheParam)
   {
      this.redisNocacheParam = redisNocacheParam;
   }

   public void setRedisReadSocketTimeout(int redisReadSocketTimeout)
   {
      this.redisReadSocketTimeout = redisReadSocketTimeout;
   }

   public void setRedisTtl(int redisTtl)
   {
      this.redisTtl = redisTtl;
   }

}
