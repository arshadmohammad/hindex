/**
 * Copyright 2011 The Apache Software Foundation
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.index.coprocessor.regionserver;

import java.io.IOException;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.TableExistsException;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.coprocessor.CoprocessorHost;
import org.apache.hadoop.hbase.filter.CompareFilter.CompareOp;
import org.apache.hadoop.hbase.filter.Filter;
import org.apache.hadoop.hbase.filter.SingleColumnValueFilter;
import org.apache.hadoop.hbase.index.ColumnQualifier.ValueType;
import org.apache.hadoop.hbase.index.IndexSpecification;
import org.apache.hadoop.hbase.index.IndexedHTableDescriptor;
import org.apache.hadoop.hbase.index.coprocessor.master.IndexMasterObserver;
import org.apache.hadoop.hbase.index.coprocessor.regionserver.MultithreadedTestUtil.RepeatingTestThread;
import org.apache.hadoop.hbase.index.coprocessor.regionserver.MultithreadedTestUtil.TestContext;
import org.apache.hadoop.hbase.index.coprocessor.wal.IndexWALObserver;
import org.apache.hadoop.hbase.testclassification.MediumTests;
import org.apache.hadoop.hbase.util.Bytes;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import com.google.common.collect.Lists;

/**
 * Test case that uses multiple threads to read and write multifamily rows into a table, verifying
 * that reads never see partially-complete writes. This can run as a junit test, or with a main()
 * function which runs against a real cluster (eg for testing with failures, region movement, etc)
 */
@Category(MediumTests.class)
public class TestAcidGuaranteesForIndex {
  protected static final Log LOG = LogFactory.getLog(TestAcidGuaranteesForIndex.class);
  public static final byte[] TABLE_NAME = Bytes.toBytes("TestAcidGuaranteesForIndex");
  public static final byte[] FAMILY_A = Bytes.toBytes("A");
  public static final byte[] FAMILY_B = Bytes.toBytes("B");
  public static final byte[] FAMILY_C = Bytes.toBytes("C");
  public static final byte[] QUALIFIER_NAME = Bytes.toBytes("data");

  public static final byte[][] FAMILIES = new byte[][] { FAMILY_A, FAMILY_B, FAMILY_C };

  private HBaseTestingUtility util;

  public static int NUM_COLS_TO_CHECK = 10;

  private void createTableIfMissing() throws IOException {
    try {
      IndexedHTableDescriptor ihtd = new IndexedHTableDescriptor(TABLE_NAME);
      for (byte[] family : FAMILIES) {
        ihtd.addFamily(new HColumnDescriptor(family));
      }
      IndexSpecification iSpec = new IndexSpecification("ScanIndex");
      iSpec.addIndexColumn(new HColumnDescriptor(Bytes.toString(FAMILY_A)),
        Bytes.toString(QUALIFIER_NAME) + "1", ValueType.String, 10);
      ihtd.addIndex(iSpec);
      util.getHBaseAdmin().createTable(ihtd);

    } catch (TableExistsException tee) {
    }
  }

  public TestAcidGuaranteesForIndex() {
    // Set small flush size for minicluster so we exercise reseeking scanners
    Configuration conf = HBaseConfiguration.create();
    conf.set(HConstants.HREGION_MEMSTORE_FLUSH_SIZE, String.valueOf(128 * 1024));
    conf.set(CoprocessorHost.MASTER_COPROCESSOR_CONF_KEY, IndexMasterObserver.class.getName());
    conf.set(CoprocessorHost.REGION_COPROCESSOR_CONF_KEY, IndexRegionObserver.class.getName());
    conf.set(CoprocessorHost.WAL_COPROCESSOR_CONF_KEY, IndexWALObserver.class.getName());
    util = new HBaseTestingUtility(conf);
  }

  /**
   * Thread that does random full-row writes into a table.
   */
  public static class AtomicityWriter extends RepeatingTestThread {
    Random rand = new Random();
    byte data[] = "value".getBytes();
    byte targetRows[][];
    byte targetFamilies[][];
    HTable table;
    AtomicLong numWritten = new AtomicLong();

    public AtomicityWriter(TestContext ctx, byte targetRows[][], byte targetFamilies[][])
        throws IOException {
      super(ctx);
      this.targetRows = targetRows;
      this.targetFamilies = targetFamilies;
      table = new HTable(ctx.getConf(), TABLE_NAME);
    }

    public void doAnAction() throws Exception {
      // Pick a random row to write into
      byte[] targetRow = targetRows[rand.nextInt(targetRows.length)];
      Put p = new Put(targetRow);
      for (byte[] family : targetFamilies) {
        for (int i = 0; i < NUM_COLS_TO_CHECK; i++) {
          byte qualifier[] = Bytes.toBytes("data" + i);
          p.add(family, qualifier, data);
        }
      }
      table.put(p);
      numWritten.getAndIncrement();
    }
  }

  /**
   * Thread that does single-row reads in a table, looking for partially completed rows.
   */
  public static class AtomicGetReader extends RepeatingTestThread {
    byte targetRow[];
    byte targetFamilies[][];
    HTable table;
    int numVerified = 0;
    AtomicLong numRead = new AtomicLong();

    public AtomicGetReader(TestContext ctx, byte targetRow[], byte targetFamilies[][])
        throws IOException {
      super(ctx);
      this.targetRow = targetRow;
      this.targetFamilies = targetFamilies;
      table = new HTable(ctx.getConf(), TABLE_NAME);
    }

    public void doAnAction() throws Exception {
      Get g = new Get(targetRow);
      Result res = table.get(g);
      byte[] gotValue = null;
      if (res.getRow() == null) {
        // Trying to verify but we didn't find the row - the writing
        // thread probably just hasn't started writing yet, so we can
        // ignore this action
        return;
      }

      for (byte[] family : targetFamilies) {
        for (int i = 0; i < NUM_COLS_TO_CHECK; i++) {
          byte qualifier[] = Bytes.toBytes("data" + i);
          byte thisValue[] = res.getValue(family, qualifier);
          if (gotValue != null && !Bytes.equals(gotValue, thisValue)) {
            gotFailure(gotValue, res);
          }
          numVerified++;
          gotValue = thisValue;
        }
      }
      numRead.getAndIncrement();
    }

    private void gotFailure(byte[] expected, Result res) {
      StringBuilder msg = new StringBuilder();
      msg.append("Failed after ").append(numVerified).append("!");
      msg.append("Expected=").append(Bytes.toStringBinary(expected));
      msg.append("Got:\n");
      for (KeyValue kv : res.list()) {
        msg.append(kv.toString());
        msg.append(" val= ");
        msg.append(Bytes.toStringBinary(kv.getValue()));
        msg.append("\n");
      }
      throw new RuntimeException(msg.toString());
    }
  }

  /**
   * Thread that does full scans of the table looking for any partially completed rows.
   */
  public static class AtomicScanReader extends RepeatingTestThread {
    byte targetFamilies[][];
    HTable table;
    AtomicLong numScans = new AtomicLong();
    AtomicLong numRowsScanned = new AtomicLong();
    Random rand = new Random();
    byte data[] = "value".getBytes();

    public AtomicScanReader(TestContext ctx, byte targetFamilies[][]) throws IOException {
      super(ctx);
      this.targetFamilies = targetFamilies;
      table = new HTable(ctx.getConf(), TABLE_NAME);
    }

    public void doAnAction() throws Exception {
      Scan s = new Scan();
      for (byte[] family : targetFamilies) {
        s.addFamily(family);
      }
      Filter filter =
          new SingleColumnValueFilter(FAMILY_A, (Bytes.toString(QUALIFIER_NAME) + "1").getBytes(),
              CompareOp.EQUAL, data);
      s.setFilter(filter);
      ResultScanner scanner = table.getScanner(s);

      for (Result res : scanner) {
        byte[] gotValue = null;

        for (byte[] family : targetFamilies) {
          for (int i = 0; i < NUM_COLS_TO_CHECK; i++) {
            byte qualifier[] = Bytes.toBytes("data" + i);
            byte thisValue[] = res.getValue(family, qualifier);
            if (gotValue != null && !Bytes.equals(gotValue, thisValue)) {
              gotFailure(gotValue, res);
            }
            gotValue = thisValue;
          }
        }
        numRowsScanned.getAndIncrement();
      }
      numScans.getAndIncrement();
    }

    private void gotFailure(byte[] expected, Result res) {
      StringBuilder msg = new StringBuilder();
      msg.append("Failed after ").append(numRowsScanned).append("!");
      msg.append("Expected=").append(Bytes.toStringBinary(expected));
      msg.append("Got:\n");
      for (KeyValue kv : res.list()) {
        msg.append(kv.toString());
        msg.append(" val= ");
        msg.append(Bytes.toStringBinary(kv.getValue()));
        msg.append("\n");
      }
      throw new RuntimeException(msg.toString());
    }
  }

  public void runTestAtomicity(long millisToRun, int numWriters, int numGetters, int numScanners,
      int numUniqueRows) throws Exception {
    createTableIfMissing();
    TestContext ctx = new TestContext(util.getConfiguration());

    byte rows[][] = new byte[numUniqueRows][];
    for (int i = 0; i < numUniqueRows; i++) {
      rows[i] = Bytes.toBytes("test_row_" + i);
    }

    List<AtomicityWriter> writers = Lists.newArrayList();
    for (int i = 0; i < numWriters; i++) {
      AtomicityWriter writer = new AtomicityWriter(ctx, rows, FAMILIES);
      writers.add(writer);
      ctx.addThread(writer);
    }
    // Add a flusher
    ctx.addThread(new RepeatingTestThread(ctx) {
      public void doAnAction() throws Exception {
        util.flush();
      }
    });

    List<AtomicGetReader> getters = Lists.newArrayList();
    for (int i = 0; i < numGetters; i++) {
      AtomicGetReader getter = new AtomicGetReader(ctx, rows[i % numUniqueRows], FAMILIES);
      getters.add(getter);
      ctx.addThread(getter);
    }

    List<AtomicScanReader> scanners = Lists.newArrayList();
    for (int i = 0; i < numScanners; i++) {
      AtomicScanReader scanner = new AtomicScanReader(ctx, FAMILIES);
      scanners.add(scanner);
      ctx.addThread(scanner);
    }

    ctx.startThreads();
    ctx.waitFor(millisToRun);
    ctx.stop();

    LOG.info("Finished test. Writers:");
    for (AtomicityWriter writer : writers) {
      LOG.info("  wrote " + writer.numWritten.get());
    }
    LOG.info("Readers:");
    for (AtomicGetReader reader : getters) {
      LOG.info("  read " + reader.numRead.get());
    }
    LOG.info("Scanners:");
    for (AtomicScanReader scanner : scanners) {
      LOG.info("  scanned " + scanner.numScans.get());
      LOG.info("  verified " + scanner.numRowsScanned.get() + " rows");
    }
  }

  @Test
  public void testGetAtomicity() throws Exception {
    util.startMiniCluster(1);
    try {
      runTestAtomicity(20000, 5, 5, 0, 3);
    } finally {
      util.shutdownMiniCluster();
    }
  }

  @Test
  public void testScanAtomicity() throws Exception {
    util.startMiniCluster(1);
    try {
      runTestAtomicity(20000, 5, 0, 5, 3);
    } finally {
      util.shutdownMiniCluster();
    }
  }

  @Test
  public void testMixedAtomicity() throws Exception {
    util.startMiniCluster(1);
    try {
      runTestAtomicity(20000, 5, 2, 2, 3);
    } finally {
      util.shutdownMiniCluster();
    }
  }

  public static void main(String args[]) throws Exception {
    Configuration c = HBaseConfiguration.create();
    TestAcidGuaranteesForIndex test = new TestAcidGuaranteesForIndex();
    test.setConf(c);
    test.runTestAtomicity(5000, 50, 2, 2, 3);
  }

  private void setConf(Configuration c) {
    util = new HBaseTestingUtility(c);
  }

  @org.junit.Rule
  public org.apache.hadoop.hbase.ResourceCheckerJUnitRule cu =
      new org.apache.hadoop.hbase.ResourceCheckerJUnitRule();
}
