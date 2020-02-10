/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.geode.internal.cache;

import static java.util.Arrays.asList;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.apache.geode.internal.cache.util.UncheckedUtils.cast;
import static org.apache.geode.test.awaitility.GeodeAwaitility.await;
import static org.apache.geode.test.dunit.IgnoredException.addIgnoredException;
import static org.apache.geode.test.dunit.rules.DistributedRule.getDistributedSystemProperties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.mockito.Mockito.mock;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Serializable;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import junitparams.naming.TestCaseName;
import org.apache.logging.log4j.Logger;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import org.apache.geode.cache.AttributesFactory;
import org.apache.geode.cache.Cache;
import org.apache.geode.cache.CacheFactory;
import org.apache.geode.cache.DataPolicy;
import org.apache.geode.cache.InterestPolicy;
import org.apache.geode.cache.Region;
import org.apache.geode.cache.RegionAttributes;
import org.apache.geode.cache.RegionFactory;
import org.apache.geode.cache.RegionShortcut;
import org.apache.geode.cache.Scope;
import org.apache.geode.cache.SubscriptionAttributes;
import org.apache.geode.internal.cache.control.InternalResourceManager;
import org.apache.geode.internal.cache.xmlcache.CacheCreation;
import org.apache.geode.internal.cache.xmlcache.CacheXmlGenerator;
import org.apache.geode.logging.internal.log4j.api.LogService;
import org.apache.geode.test.dunit.AsyncInvocation;
import org.apache.geode.test.dunit.SerializableRunnableIF;
import org.apache.geode.test.dunit.VM;
import org.apache.geode.test.dunit.rules.DistributedRule;
import org.apache.geode.test.junit.rules.serializable.SerializableTemporaryFolder;
import org.apache.geode.test.junit.rules.serializable.SerializableTestName;

@RunWith(JUnitParamsRunner.class)
@SuppressWarnings("serial")
public class DestroyRegionDuringGIIDistributedTest implements Serializable {
  private static final Logger logger = LogService.getLogger();

  private static final long POLL_INTERVAL_MILLIS = 50;
  private static final int NB1_CHUNK_SIZE = 500 * 1024; // ==
  private static final int NB1_NUM_ENTRIES = 1000;
  private static final int NB1_VALUE_SIZE = NB1_CHUNK_SIZE * 10 / NB1_NUM_ENTRIES;

  private static final InternalCache DUMMY_CACHE = mock(InternalCache.class);

  private static final AtomicReference<InternalCache> CACHE = new AtomicReference<>(DUMMY_CACHE);

  private String rootRegionName;
  private String regionName;

  private VM vm0;
  private VM vm1;
  private VM vm2;
  private VM vm3;

  @Rule
  public DistributedRule distributedRule = new DistributedRule();
  @Rule
  public SerializableTemporaryFolder temporaryFolder = new SerializableTemporaryFolder();
  @Rule
  public SerializableTestName testName = new SerializableTestName();

  @Before
  public void setUp() {
    vm0 = VM.getVM(0);
    vm1 = VM.getVM(1);
    vm2 = VM.getVM(2);
    vm3 = VM.getVM(3);

    regionName = getUniqueName() + "_region";
    rootRegionName = getUniqueName() + "_rootRegion";

    for (VM memberVM : asList(vm0, vm1, vm2, vm3)) {
      memberVM.invoke(() -> {
        CACHE.set(DUMMY_CACHE);
//        DISK_DIR.set(temporaryFolder.newFolder("diskDir-" + getVMId()).getAbsoluteFile());
      });
    }
  }

  @After
  public void tearDown() {
    for (VM vm : asList(vm0, vm1, vm2, vm3)) {
      vm.invoke(() -> {
        InternalResourceManager.setResourceObserver(null);
        closeCache();
//        DISK_DIR.set(null);
      });
    }
  }

  /**
   * Returns the attributes of a region to be tested by this test. Note that the decision as to
   * which attributes are used is left up to the concrete subclass.
   */
  private <K, V> RegionAttributes<K, V> getRegionAttributes() {
    AttributesFactory<K, V> factory = new AttributesFactory<>();
    factory.setScope(Scope.DISTRIBUTED_ACK);
    factory.setDataPolicy(DataPolicy.PRELOADED);
    factory.setConcurrencyChecksEnabled(false);
    return factory.create();
  }

  private enum RegionDefinition {
    DISTRIBUTED_ACK(cache -> cache.createRegionFactory()
        .setDataPolicy(DataPolicy.PRELOADED)
        .setConcurrencyChecksEnabled(false));

    private final Function<Cache, RegionFactory> function;

    RegionDefinition(Function<Cache, RegionFactory> function) {
      this.function = function;
    }

    private <K, V> RegionFactory<K, V> createRegionFactory(Cache cache) {
      return cast(function.apply(cache));
    }
  }

  private void createRegion(RegionShortcut regionShortcut, String regionName) {
    getCache()
        .createRegionFactory(regionShortcut)
        .create(regionName);
  }

  @Test
  @Parameters({"DISTRIBUTED_ACK,REPLICATE", "DISTRIBUTED_ACK,PERSISTENT_REPLICATE"})
  @TestCaseName("{method}({params})")
  public void testNBRegionDestructionDuringGetInitialImage(RegionDefinition regionDefinition, RegionShortcut regionShortcut)
      throws InterruptedException {
    assumeThat(supportsReplication()).isTrue();

    final String name = getUniqueName();
    final byte[][] values = new byte[NB1_NUM_ENTRIES][];

    for (int i = 0; i < NB1_NUM_ENTRIES; i++) {
      values[i] = new byte[NB1_VALUE_SIZE];
      Arrays.fill(values[i], (byte) 0x42);
    }

    vm0.invoke("Create Nonmirrored Region", () -> {
      createCache();
      createRootRegion();

      regionDefinition
          .createRegionFactory(getCache())
          .create(name);

      // reset slow
      InitialImageOperation.slowImageProcessing = 0;

      Region<Integer, byte[]> region = getCache().getRegion(name);
      for (int i = 0; i < NB1_NUM_ENTRIES; i++) {
        region.put(i, values[i]);
      }
      assertThat(region.keySet().size()).isEqualTo(NB1_NUM_ENTRIES);
    });

    // start asynchronous process that does updates to the data
    AsyncInvocation async =
        vm0.invokeAsync("Do Nonblocking Operations", () -> {
          await().until(() -> getCache().getCachePerfStats().getGetInitialImagesCompleted() < 1);
          Region<Object, Object> region = getCache().getRegion(name);

          // wait for profile of getInitialImage cache to show up
          final CacheDistributionAdvisor adv =
              ((DistributedRegion) region)
                  .getCacheDistributionAdvisor();
          // int numProfiles;
          final int expectedProfiles = 1;
          await("profile count never became exactly " + expectedProfiles)
              .until(() -> adv.adviseReplicates().size(), equalTo(expectedProfiles));

          // since we want to force a GII while updates are flying, make sure
          // the other VM gets its CreateRegionResponse and starts its GII
          // before falling into the update loop

          // operate on every odd entry with different value, alternating between
          // updates, invalidates, and destroys. These operations are likely
          // to be nonblocking if a sufficient number of updates get through
          // before the get initial image is complete.
          for (int i = 1; i < 301; i += 2) {
            Object key = i;
            switch (i % 6) {
              case 1: // UPDATE
                // use the current timestamp so we know when it happened
                // we could have used last modification timestamps, but
                // this works without enabling statistics
                Object value = System.currentTimeMillis();
                region.put(key, value);
                break;
              case 3: // INVALIDATE
                region.invalidate(key);
                if (region.getAttributes().getScope().isDistributedAck()) {
                  // do a nonblocking netSearch
                  value = region.get(key);
                  assertThat(value).describedAs(
                      "Expected null value for key: " + i + " but got " + value).isNull();
                }
                break;
              case 5: // DESTROY
                region.destroy(key);
                if (region.getAttributes().getScope().isDistributedAck()) {
                  // do a nonblocking netSearch
                  assertThat(region.get(key)).isNull();
                }
                break;
              default:
                fail("unexpected modulus result: " + i);
                break;
            }
          }

          region.destroyRegion();
          // now do a put and our DACK root region which will not complete
          // until processed on otherside which means everything done before this
          // point has been processed
          {
            Region<String, String> rr = getRootRegion();
            if (rr != null) {
              rr.put("DONE", "FLUSH_OPS");
            }
          }
        });

    addIgnoredException("RegionDestroyedException");

      // in the meantime, do the get initial image in vm2
      AsyncInvocation asyncGII =
          vm2.invokeAsync("Create Mirrored Region", () -> {
            if (!getRegionAttributes().getScope().isGlobal()) {
              InitialImageOperation.slowImageProcessing = 200;
            }

            beginCacheXml();

            // root region must be DACK because its used to sync up async subregions
              createRootRegion(getCache()
                  .createRegionFactory()
                  .setDataPolicy(DataPolicy.NORMAL)
                  .setScope(Scope.DISTRIBUTED_ACK)
                  .setSubscriptionAttributes(new SubscriptionAttributes(InterestPolicy.ALL)));

              createRegion(RegionShortcut.REPLICATE, name);

            finishCacheXml(name);
            // reset slow
            InitialImageOperation.slowImageProcessing = 0;
            // if global scope, the region doesn't get destroyed until after region creation
            await().pollDelay(1, SECONDS)
                .untilAsserted(() -> assertThat(getCache().getRegion(name) == null
                    || getRegionAttributes().getScope().isGlobal()).isTrue());

          });

      if (getRegionAttributes().getScope().isGlobal()) {
        // wait for nonblocking operations to complete
        async.get();

        vm2.invoke("Set fast image processing", () -> {
          InitialImageOperation.slowImageProcessing = 0;
        });
      }

      // wait for GII to complete
      // getLogWriter().info("starting wait for GetInitialImage Completion");
      asyncGII.get();
      if (getRegionAttributes().getScope().isGlobal()) {
        // wait for nonblocking operations to complete
        async.get();
      }
  }

  @Test
  public void testNBRegionInvalidationDuringGetInitialImage() throws Exception {
    assumeThat(supportsReplication()).isTrue();

    // don't run this for noAck, too many race conditions
    if (getRegionAttributes().getScope().isDistributedNoAck()) {
      return;
    }

    String regionName = getUniqueName();
    final byte[][] values = new byte[NB1_NUM_ENTRIES][];

    for (int i = 0; i < NB1_NUM_ENTRIES; i++) {
      values[i] = new byte[NB1_VALUE_SIZE];
      Arrays.fill(values[i], (byte) 0x42);
    }

    vm0.invoke("Create Nonmirrored Region", () -> {
      // root region must be DACK because its used to sync up async subregions
      createRootRegion(getCache()
          .createRegionFactory()
          .setDataPolicy(DataPolicy.NORMAL)
          .setScope(Scope.DISTRIBUTED_ACK));

        createRegion(RegionShortcut.REPLICATE, regionName);

      // reset slow
      InitialImageOperation.slowImageProcessing = 0;
    });

    vm0.invoke("Put initial data", () -> {
      Region<Integer, byte[]> region = getCache().getRegion(regionName);
      for (int i = 0; i < NB1_NUM_ENTRIES; i++) {
        region.put(i, values[i]);
      }
      assertThat(region.keySet().size()).isEqualTo(NB1_NUM_ENTRIES);
    });

    // start asynchronous process that does updates to the data
    AsyncInvocation async = vm0.invokeAsync("Do Nonblocking Operations", () -> {
      Region<Object, Object> region = getCache().getRegion(regionName);

      // wait for profile of getInitialImage cache to show up
      final CacheDistributionAdvisor adv =
          ((DistributedRegion) region)
              .getCacheDistributionAdvisor();
      final int expectedProfiles = 1;

      await("profile count never reached " + expectedProfiles)
          .until(() -> adv.adviseReplicates().size(), greaterThanOrEqualTo(expectedProfiles));

      // operate on every odd entry with different value, alternating between
      // updates, invalidates, and destroys. These operations are likely
      // to be nonblocking if a sufficient number of updates get through
      // before the get initial image is complete.
      for (int i = 1; i < NB1_NUM_ENTRIES; i += 2) {

        // at magical number 301, do a region invalidation, then continue
        // as before
        if (i == 301) {
          // wait for previous updates to be processed
          flushIfNecessary(region);
          region.invalidateRegion();
          flushIfNecessary(region);
        }

        Object key = i;
        switch (i % 6) {
          case 1: // UPDATE
            // use the current timestamp so we know when it happened
            // we could have used last modification timestamps, but
            // this works without enabling statistics
            Object value = System.currentTimeMillis();
            region.put(key, value);
            break;
          case 3: // INVALIDATE
            region.invalidate(key);
            if (getRegionAttributes().getScope().isDistributedAck()) {
              // do a nonblocking netSearch
              value = region.get(key);
              assertThat(value).describedAs(
                  "Expected null value for key: " + i + " but got " + value).isNull();
            }
            break;
          case 5: // DESTROY
            region.destroy(key);
            if (getRegionAttributes().getScope().isDistributedAck()) {
              // do a nonblocking netSearch
              assertThat(region.get(key)).isNull();
            }
            break;
          default:
            fail("unexpected modulus result: " + i);
            break;
        }
      }
      // now do a put and our DACK root region which will not complete
      // until processed on otherside which means everything done before this
      // point has been processed
      getRootRegion().put("DONE", "FLUSH_OPS");
    });

    // in the meantime, do the get initial image in vm2
    // slow down image processing to make it more likely to get async updates
    if (!getRegionAttributes().getScope().isGlobal()) {
      vm2.invoke("Set slow image processing", () -> {
        // make sure the cache is set up before turning on slow
        // image processing
        getRootRegion();
        // if this is a no_ack test, then we need to slow down more because of the
        // pauses in the nonblocking operations
        InitialImageOperation.slowImageProcessing = 100;
      });
    }

    AsyncInvocation asyncGII = vm2.invokeAsync("Create Mirrored Region", () -> {
      beginCacheXml();
      // root region must be DACK because its used to sync up async subregions

        createRootRegion(getCache()
            .createRegionFactory()
            .setDataPolicy(DataPolicy.NORMAL)
            .setScope(Scope.DISTRIBUTED_ACK)
            .setSubscriptionAttributes(new SubscriptionAttributes(InterestPolicy.ALL)));

        createRegion(RegionShortcut.REPLICATE, regionName);

      finishCacheXml(regionName);
      // reset slow
      InitialImageOperation.slowImageProcessing = 0;
    });

    if (!getRegionAttributes().getScope().isGlobal()) {
      // wait for nonblocking operations to complete
      try {
        async.get();
      } finally {
        vm2.invoke("Set fast image processing", () -> {
          InitialImageOperation.slowImageProcessing = 0;
        });
      }
    }

    // wait for GII to complete
    asyncGII.get();
    final long iiComplete = System.currentTimeMillis();
    logger.info("Complete GetInitialImage at: " + System.currentTimeMillis());
    if (getRegionAttributes().getScope().isGlobal()) {
      // wait for nonblocking operations to complete
      async.get();
    }

    // Locally destroy the region in vm0 so we know that they are not found by
    // a netSearch
    vm0.invoke("Locally destroy region", () -> {
      Region<Object, Object> region = getCache().getRegion(regionName);
      region.localDestroyRegion();
    });

    // invoke repeating so noack regions wait for all updates to get processed
    vm2.invoke("Verify entryCount", repeatingIfNecessary(3000, () -> {
      Region<Object, Object> region = getCache().getRegion(regionName);
      // expected entry count (subtract entries destroyed)
      int entryCount = NB1_NUM_ENTRIES - NB1_NUM_ENTRIES / 6;
      int actualCount = region.entrySet(false).size();
      assertThat(actualCount).isEqualTo(entryCount);
    }));

    vm2.invoke("Verify keys/values & Nonblocking", () -> {
      Region<Integer, Object> region = getCache().getRegion(regionName);
      // expected entry count (subtract entries destroyed)
      int entryCount = NB1_NUM_ENTRIES - NB1_NUM_ENTRIES / 6;
      assertThat(region.entrySet(false).size()).isEqualTo(entryCount);
      // determine how many entries were updated before getInitialImage
      // was complete
      int numConcurrent = 0;

      for (int i = 0; i < NB1_NUM_ENTRIES; i++) {
        Region.Entry entry = region.getEntry(i);
        if (i < 301) {
          if (i % 6 == 5) {
            // destroyed
            assertThat(entry).describedAs(
                "Expected entry for " + i + " to be destroyed but it is " + entry).isNull();
          } else {
            assertThat(entry).isNotNull();
            Object v = entry.getValue();
            assertThat(v).describedAs("Expected value for " + i + " to be null, but was " + v)
                .isNull();
          }
        } else {
          Object v = entry == null ? null : entry.getValue();
          switch (i % 6) {
            // even keys are originals
            case 0:
            case 2:
            case 4:
              assertThat(entry).isNotNull();
              assertThat(v).describedAs("Expected value for " + i + " to be null, but was " + v)
                  .isNull();
              break;
            case 1: // updated
              assertThat(entry).describedAs("Expected to find an entry for #" + i).isNotNull();
              assertThat(v).describedAs("Expected to find a value for #" + i).isNotNull();
              boolean condition = v instanceof Long;
              assertThat(condition).describedAs(
                  "Value for key " + i + " is not a Long, is a " + v.getClass().getName())
                  .isTrue();
              Long timestamp = (Long) entry.getValue();
              if (timestamp < iiComplete) {
                numConcurrent++;
              }
              break;
            case 3: // invalidated
              assertThat(entry).describedAs("Expected to find an entry for #" + i).isNotNull();
              assertThat(v).describedAs("Expected value for " + i + " to be null, but was " + v)
                  .isNull();
              break;
            case 5: // destroyed
              assertThat(entry).describedAs("Expected to not find an entry for #" + i).isNull();
              break;
            default:
              fail("unexpected modulus result: " + (i % 6));
              break;
          }
        }
      }

      // Looks like some random expectations that will always be a hit/miss.

      // make sure at least some of them were concurrent
      if (getRegionAttributes().getScope().isGlobal()) {
        assertThat(numConcurrent < 300).describedAs(
            "Too many concurrent updates when expected to block: " + numConcurrent).isTrue();
      } else {
        assertThat(numConcurrent >= 30).describedAs(
            "Not enough updates concurrent with getInitialImage occurred to my liking. "
                + numConcurrent + " entries out of " + entryCount
                + " were updated concurrently with getInitialImage, and I'd expect at least 50 or so")
            .isTrue();
      }
    });
  }

  private String getUniqueName() {
    return getClass().getSimpleName() + "_" + testName.getMethodName();
  }

  private void createCache() {
    CACHE.set((InternalCache) new CacheFactory(getDistributedSystemProperties()).create());
  }

  private InternalCache getCache() {
    return CACHE.get();
  }

  private void closeCache() {
    CACHE.getAndSet(DUMMY_CACHE).close();
  }

  private void createRootRegion() {
    createRootRegion(getCache()
        .createRegionFactory()
        .setDataPolicy(DataPolicy.EMPTY)
        .setScope(Scope.DISTRIBUTED_ACK));
  }

  private void createRootRegion(RegionFactory regionFactory) {
    regionFactory.create(rootRegionName);
  }

  private Region getRootRegion() {
    return getCache().getRegion(rootRegionName);
  }

  /**
   * Decorates the given runnable to make it repeat every {@link #POLL_INTERVAL_MILLIS} until either
   * it terminates without throwing or the given timeout expires. If the timeout is less than or
   * equal to {@code POLL_INTERVAL_MILLIS}, the given runnable is returned undecorated.
   *
   * @param timeoutMillis the maximum length of time (in milliseconds) to repeat the runnable
   * @param runnable the runnable to run
   */
  private SerializableRunnableIF repeatingIfNecessary(long timeoutMillis,
                                                      SerializableRunnableIF runnable) {
    if (timeoutMillis > POLL_INTERVAL_MILLIS) {
      return () -> await()
          .untilAsserted(runnable::run);
    }
    return runnable;
  }

  /**
   * Indicate whether replication/GII supported
   *
   * @return true if replication is supported
   */
  private boolean supportsReplication() {
    return true;
  }

  /**
   * Make sure all messages done on region r have been processed on the remote side.
   */
  private void flushIfNecessary(Region r) {
    // Only needed for no-ack regions
  }

  /**
   * Sets this test up with a {@code CacheCreation} as its cache. Any existing cache is closed.
   * Whoever calls this must also call {@code finishCacheXml}.
   */
  public void beginCacheXml() {
    closeCache();
    CACHE.set(new TestCacheCreation());
  }

  /**
   * Finish what {@code beginCacheXml} started. It does this be generating a cache.xml file and then
   * creating a real cache using that cache.xml.
   */
  private void finishCacheXml(final String name) {
      try {
        File file = new File(name + "-cache.xml");
        PrintWriter printWriter = new PrintWriter(new FileWriter(file), true);
        CacheXmlGenerator.generate(getCache(), printWriter);
        printWriter.close();
        closeCache();
        GemFireCacheImpl.testCacheXml = file;
        createCache();
      } catch (Exception ex) {
        throw new RuntimeException(ex);
      } finally {
        GemFireCacheImpl.testCacheXml = null;
      }
  }

  /**
   * Finish what {@code beginCacheXml} started. It does this be generating a cache.xml file and then
   * creating a real cache using that cache.xml.
   */
  private void finishCacheXml(final File root, final String name, final boolean useSchema,
                                   final String xmlVersion) throws IOException {
    File dir = new File(root, "XML_" + xmlVersion);
    dir.mkdirs();
    File file = new File(dir, name + ".xml");
    PrintWriter printWriter = new PrintWriter(new FileWriter(file), true);
    CacheXmlGenerator.generate(getCache(), printWriter, useSchema, xmlVersion);
    printWriter.close();
    closeCache();
    GemFireCacheImpl.testCacheXml = file;
    try {
      createCache();
    } finally {
      GemFireCacheImpl.testCacheXml = null;
    }
  }

  /**
   * Used to generate a cache.xml. Basically just a {@code CacheCreation} with a few more methods
   * implemented.
   */
  private static class TestCacheCreation extends CacheCreation {

    private final AtomicBoolean closed = new AtomicBoolean();

    @Override
    public void close() {
      closed.set(true);
    }

    @Override
    public boolean isClosed() {
      return closed.get();
    }
  }
}
