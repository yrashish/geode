/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.geode.internal;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;

import org.junit.Test;
import org.junit.experimental.categories.Category;

import org.apache.geode.DataSerializable;
import org.apache.geode.cache.Cache;
import org.apache.geode.internal.cache.DistributedPutAllOperation.EntryVersionsList;
import org.apache.geode.internal.serialization.DataSerializableFixedID;
import org.apache.geode.internal.serialization.DeserializationContext;
import org.apache.geode.internal.serialization.KnownVersion;
import org.apache.geode.internal.serialization.SerializationContext;
import org.apache.geode.internal.serialization.SerializationVersions;
import org.apache.geode.internal.serialization.VersionedDataInputStream;
import org.apache.geode.internal.serialization.VersionedDataOutputStream;
import org.apache.geode.internal.serialization.internal.DSFIDSerializerImpl;
import org.apache.geode.test.dunit.cache.internal.JUnit4CacheTestCase;
import org.apache.geode.test.junit.categories.SerializationTest;

/**
 * Test the DSFID serialization framework added for rolling upgrades in 7.1
 *
 *
 *
 */
@Category({SerializationTest.class})
public class BackwardCompatibilitySerializationDUnitTest extends JUnit4CacheTestCase {

  private transient ByteArrayOutputStream baos;
  private transient ByteArrayInputStream bais;

  public static boolean toDataCalled = false;
  public static boolean toDataPre66Called = false;
  public static boolean toDataPre70called = false;
  public static boolean fromDataCalled = false;
  public static boolean fromDataPre66Called = false;
  public static boolean fromDataPre70Called = false;

  public TestMessage msg = new TestMessage();

  public BackwardCompatibilitySerializationDUnitTest() {
    super();
  }

  @Override
  public final void postSetUp() {
    baos = new ByteArrayOutputStream();
    // register TestMessage using an existing dsfid
    InternalDataSerializer.getDSFIDSerializer()
        .registerDSFID(DataSerializableFixedID.PUTALL_VERSIONS_LIST, TestMessage.class);
  }

  @Override
  public final void preTearDownCacheTestCase() {
    resetFlags();
    // reset the class mapped to the dsfid
    InternalDataSerializer.getDSFIDSerializer().registerDSFID(
        DataSerializableFixedID.PUTALL_VERSIONS_LIST,
        EntryVersionsList.class);
    this.baos = null;
    this.bais = null;
  }

  /**
   * Test if correct toData/toDataPreXXX is called when changes are made to the TestMessage in 66
   * and 70 and version of peer is 56
   *
   */
  @Test
  public void testToDataFromHigherVersionToLower() throws Exception {
    DataOutputStream dos =
        new VersionedDataOutputStream(new DataOutputStream(baos), KnownVersion.GFE_56);
    InternalDataSerializer.writeDSFID(msg, dos);
    assertTrue(toDataPre66Called);
    assertFalse(toDataCalled);
  }

  /**
   * Test if correct toData/toDataXXX is called when changes are made to the TestMessage in 66 and
   * 70 and version of peer is 70
   *
   */
  @Test
  public void testToDataFromLowerVersionToHigher() throws Exception {
    DataOutputStream dos =
        new VersionedDataOutputStream(new DataOutputStream(baos), KnownVersion.GFE_701);
    InternalDataSerializer.writeDSFID(msg, dos);
    assertTrue(toDataCalled);
  }

  /**
   * Test if correct fromData/fromDataXXX is called when changes are made to the TestMessage in 66
   * and 70 and version of peer is 70
   *
   */
  @Test
  public void testFromDataFromHigherVersionToLower() throws Exception {
    InternalDataSerializer.writeDSFID(msg, new DataOutputStream(baos));
    this.bais = new ByteArrayInputStream(baos.toByteArray());

    DataInputStream dis =
        new VersionedDataInputStream(new DataInputStream(bais), KnownVersion.GFE_701);
    Object o = InternalDataSerializer.basicReadObject(dis);
    assertTrue(o instanceof TestMessage);
    assertTrue(fromDataCalled);
  }

  /**
   * Test if correct fromData/fromDataXXX is called when changes are made to the TestMessage in 66
   * and 70 and version of peer is 56
   *
   */
  @Test
  public void testFromDataFromLowerVersionToHigher() throws Exception {
    InternalDataSerializer.writeDSFID(msg, new DataOutputStream(baos));
    this.bais = new ByteArrayInputStream(baos.toByteArray());

    DataInputStream dis =
        new VersionedDataInputStream(new DataInputStream(bais), KnownVersion.GFE_56);
    Object o = InternalDataSerializer.basicReadObject(dis);
    assertTrue(o instanceof TestMessage);
    assertTrue(fromDataPre66Called);
  }

  /**
   * Test if all messages implement toDataPreXXX and fromDataPreXXX if the message has been upgraded
   * in any of the versions
   *
   */
  @Test
  public void testAllMessages() throws Exception {
    // list of msgs not created using reflection
    // taken from DSFIDFactory.create()
    ArrayList<Integer> constdsfids = new ArrayList<Integer>();
    constdsfids.add(new Byte(DataSerializableFixedID.REGION).intValue());
    constdsfids.add(new Byte(DataSerializableFixedID.END_OF_STREAM_TOKEN).intValue());
    constdsfids.add(new Byte(DataSerializableFixedID.DLOCK_REMOTE_TOKEN).intValue());
    constdsfids.add(new Byte(DataSerializableFixedID.TRANSACTION_ID).intValue());
    constdsfids.add(new Byte(DataSerializableFixedID.INTEREST_RESULT_POLICY).intValue());
    constdsfids.add(new Byte(DataSerializableFixedID.UNDEFINED).intValue());
    constdsfids.add(new Byte(DataSerializableFixedID.RESULTS_BAG).intValue());
    constdsfids.add(new Byte(DataSerializableFixedID.GATEWAY_EVENT_IMPL_66).intValue());
    constdsfids.add(new Short(DataSerializableFixedID.TOKEN_INVALID).intValue());
    constdsfids.add(new Short(DataSerializableFixedID.TOKEN_LOCAL_INVALID).intValue());
    constdsfids.add(new Short(DataSerializableFixedID.TOKEN_DESTROYED).intValue());
    constdsfids.add(new Short(DataSerializableFixedID.TOKEN_REMOVED).intValue());
    constdsfids.add(new Short(DataSerializableFixedID.TOKEN_REMOVED2).intValue());
    constdsfids.add(new Short(DataSerializableFixedID.TOKEN_TOMBSTONE).intValue());

    for (int i = 0; i < 256; i++) {
      Constructor<?> cons =
          ((DSFIDSerializerImpl) InternalDataSerializer.getDSFIDSerializer()).getDsfidmap()[i];
      if (!constdsfids.contains(i - Byte.MAX_VALUE - 1) && cons != null) {
        Object ds = cons.newInstance((Object[]) null);
        checkSupportForRollingUpgrade(ds);
      }
    }

    // some msgs require distributed system
    Cache c = getCache();
    for (Object o : ((DSFIDSerializerImpl) InternalDataSerializer.getDSFIDSerializer())
        .getDsfidmap2().values()) {
      Constructor<?> cons = (Constructor<?>) o;
      if (cons != null) {
        DataSerializableFixedID ds = (DataSerializableFixedID) cons.newInstance((Object[]) null);
        checkSupportForRollingUpgrade(ds);
      }
    }
    c.close();
  }

  private void checkSupportForRollingUpgrade(Object ds) {
    KnownVersion[] versions = null;
    if (ds instanceof SerializationVersions) {
      versions = ((SerializationVersions) ds).getSerializationVersions();
    }
    if (versions != null && versions.length > 0) {
      for (int i = 0; i < versions.length; i++) {
        if (ds instanceof DataSerializableFixedID) {
          try {
            ds.getClass().getMethod("toDataPre_" + versions[i].getMethodSuffix(),
                new Class[] {DataOutput.class, SerializationContext.class});

            ds.getClass().getMethod("fromDataPre_" + versions[i].getMethodSuffix(),
                new Class[] {DataInput.class, DeserializationContext.class});
          } catch (NoSuchMethodException e) {
            fail(
                "toDataPreXXX or fromDataPreXXX for previous versions not found " + e.getMessage());
          }
        }
        if (ds instanceof DataSerializable) {
          try {
            ds.getClass().getMethod("toDataPre_" + versions[i].getMethodSuffix(),
                new Class[] {DataOutput.class});

            ds.getClass().getMethod("fromDataPre_" + versions[i].getMethodSuffix(),
                new Class[] {DataInput.class});
          } catch (NoSuchMethodException e) {
            fail(
                "toDataPreXXX or fromDataPreXXX for previous versions not found " + e.getMessage());
          }
        }
      }
    } else {
      for (Method method : ds.getClass().getMethods()) {
        if (method.getName().startsWith("toDataPre")) {
          fail(
              "Found backwards compatible toData, but class does not implement getSerializationVersions()"
                  + method);
        } else if (method.getName().startsWith("fromDataPre")) {
          fail(
              "Found backwards compatible fromData, but class does not implement getSerializationVersions()"
                  + method);
        }
      }

    }
  }

  private void resetFlags() {
    toDataCalled = false;
    toDataPre66Called = false;
    toDataPre70called = false;
    fromDataCalled = false;
    fromDataPre66Called = false;
    fromDataPre70Called = false;
  }

  public static class TestMessage implements DataSerializableFixedID {
    /** The versions in which this message was modified */
    private static final KnownVersion[] dsfidVersions =
        new KnownVersion[] {KnownVersion.GFE_66, KnownVersion.GFE_70};

    public TestMessage() {}

    @Override
    public KnownVersion[] getSerializationVersions() {
      return dsfidVersions;
    }

    @Override
    public void toData(DataOutput out,
        SerializationContext context) throws IOException {
      toDataCalled = true;
    }

    public void toDataPre_GFE_6_6_0_0(DataOutput out, SerializationContext context)
        throws IOException {
      toDataPre66Called = true;
    }

    public void toDataPre_GFE_7_0_0_0(DataOutput out, SerializationContext context)
        throws IOException {
      toDataPre70called = true;
    }

    @Override
    public void fromData(DataInput in,
        DeserializationContext context) throws IOException {
      fromDataCalled = true;
    }

    public void fromDataPre_GFE_6_6_0_0(DataInput out, DeserializationContext context)
        throws IOException {
      fromDataPre66Called = true;
    }

    public void fromDataPre_GFE_7_0_0_0(DataInput out, DeserializationContext context)
        throws IOException {
      fromDataPre70Called = true;
    }

    @Override
    public int getDSFID() {
      return DataSerializableFixedID.PUTALL_VERSIONS_LIST;
    }

  }
}
