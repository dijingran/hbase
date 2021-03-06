/**
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
package org.apache.hadoop.hbase.master;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.ZooKeeperConnectionException;
import org.apache.hadoop.hbase.catalog.CatalogTracker;
import org.apache.hadoop.hbase.client.AdminProtocol;
import org.apache.hadoop.hbase.client.ClientProtocol;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.ipc.ProtocolSignature;
import org.apache.hadoop.hbase.ipc.RpcServer;
import org.apache.hadoop.hbase.protobuf.ProtobufUtil;
import org.apache.hadoop.hbase.protobuf.generated.AdminProtos.CloseRegionRequest;
import org.apache.hadoop.hbase.protobuf.generated.AdminProtos.CloseRegionResponse;
import org.apache.hadoop.hbase.protobuf.generated.AdminProtos.CompactRegionRequest;
import org.apache.hadoop.hbase.protobuf.generated.AdminProtos.CompactRegionResponse;
import org.apache.hadoop.hbase.protobuf.generated.AdminProtos.FlushRegionRequest;
import org.apache.hadoop.hbase.protobuf.generated.AdminProtos.FlushRegionResponse;
import org.apache.hadoop.hbase.protobuf.generated.AdminProtos.GetOnlineRegionRequest;
import org.apache.hadoop.hbase.protobuf.generated.AdminProtos.GetOnlineRegionResponse;
import org.apache.hadoop.hbase.protobuf.generated.AdminProtos.GetRegionInfoRequest;
import org.apache.hadoop.hbase.protobuf.generated.AdminProtos.GetRegionInfoResponse;
import org.apache.hadoop.hbase.protobuf.generated.AdminProtos.GetServerInfoRequest;
import org.apache.hadoop.hbase.protobuf.generated.AdminProtos.GetServerInfoResponse;
import org.apache.hadoop.hbase.protobuf.generated.AdminProtos.GetStoreFileRequest;
import org.apache.hadoop.hbase.protobuf.generated.AdminProtos.GetStoreFileResponse;
import org.apache.hadoop.hbase.protobuf.generated.AdminProtos.OpenRegionRequest;
import org.apache.hadoop.hbase.protobuf.generated.AdminProtos.OpenRegionResponse;
import org.apache.hadoop.hbase.protobuf.generated.AdminProtos.ReplicateWALEntryRequest;
import org.apache.hadoop.hbase.protobuf.generated.AdminProtos.ReplicateWALEntryResponse;
import org.apache.hadoop.hbase.protobuf.generated.AdminProtos.RollWALWriterRequest;
import org.apache.hadoop.hbase.protobuf.generated.AdminProtos.RollWALWriterResponse;
import org.apache.hadoop.hbase.protobuf.generated.AdminProtos.SplitRegionRequest;
import org.apache.hadoop.hbase.protobuf.generated.AdminProtos.SplitRegionResponse;
import org.apache.hadoop.hbase.protobuf.generated.AdminProtos.StopServerRequest;
import org.apache.hadoop.hbase.protobuf.generated.AdminProtos.StopServerResponse;
import org.apache.hadoop.hbase.protobuf.generated.ClientProtos;
import org.apache.hadoop.hbase.protobuf.generated.ClientProtos.BulkLoadHFileRequest;
import org.apache.hadoop.hbase.protobuf.generated.ClientProtos.BulkLoadHFileResponse;
import org.apache.hadoop.hbase.protobuf.generated.ClientProtos.GetRequest;
import org.apache.hadoop.hbase.protobuf.generated.ClientProtos.GetResponse;
import org.apache.hadoop.hbase.protobuf.generated.ClientProtos.LockRowRequest;
import org.apache.hadoop.hbase.protobuf.generated.ClientProtos.LockRowResponse;
import org.apache.hadoop.hbase.protobuf.generated.ClientProtos.MultiRequest;
import org.apache.hadoop.hbase.protobuf.generated.ClientProtos.MutateRequest;
import org.apache.hadoop.hbase.protobuf.generated.ClientProtos.MutateResponse;
import org.apache.hadoop.hbase.protobuf.generated.ClientProtos.ScanRequest;
import org.apache.hadoop.hbase.protobuf.generated.ClientProtos.ScanResponse;
import org.apache.hadoop.hbase.protobuf.generated.ClientProtos.UnlockRowRequest;
import org.apache.hadoop.hbase.protobuf.generated.ClientProtos.UnlockRowResponse;
import org.apache.hadoop.hbase.regionserver.CompactionRequestor;
import org.apache.hadoop.hbase.regionserver.FlushRequester;
import org.apache.hadoop.hbase.regionserver.HRegion;
import org.apache.hadoop.hbase.regionserver.Leases;
import org.apache.hadoop.hbase.regionserver.RegionServerAccounting;
import org.apache.hadoop.hbase.regionserver.RegionServerServices;
import org.apache.hadoop.hbase.regionserver.wal.HLog;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.zookeeper.ZooKeeperWatcher;
import org.apache.zookeeper.KeeperException;

import com.google.protobuf.RpcController;
import com.google.protobuf.ServiceException;

/**
 * A mock RegionServer implementation.
 * Use this when you can't bend Mockito to your liking (e.g. return null result
 * when 'scanning' until master timesout and then return a coherent meta row
 * result thereafter.  Have some facility for faking gets and scans.  See
 * {@link #setGetResult(byte[], byte[], Result)} for how to fill the backing data
 * store that the get pulls from.
 */
class MockRegionServer implements AdminProtocol, ClientProtocol, RegionServerServices {
  private final ServerName sn;
  private final ZooKeeperWatcher zkw;
  private final Configuration conf;
  private final Random random = new Random();

  /**
   * Map of regions to map of rows and {@link Results}.  Used as data source when
   * {@link MockRegionServer#get(byte[], Get)} is called. Because we have a byte
   * key, need to use TreeMap and provide a Comparator.  Use
   * {@link #setGetResult(byte[], byte[], Result)} filling this map.
   */
  private final Map<byte [], Map<byte [], Result>> gets =
    new TreeMap<byte [], Map<byte [], Result>>(Bytes.BYTES_COMPARATOR);

  /**
   * Map of regions to results to return when scanning.
   */
  private final Map<byte [], Result []> nexts =
    new TreeMap<byte [], Result []>(Bytes.BYTES_COMPARATOR);

  /**
   * Data structure that holds regionname and index used scanning.
   */
  class RegionNameAndIndex {
    private final byte[] regionName;
    private int index = 0;

    RegionNameAndIndex(final byte[] regionName) {
      this.regionName = regionName;
    }

    byte[] getRegionName() {
      return this.regionName;
    }

    int getThenIncrement() {
      int currentIndex = this.index;
      this.index++;
      return currentIndex;
    }
  }

  /**
   * Outstanding scanners and their offset into <code>nexts</code>
   */
  private final Map<Long, RegionNameAndIndex> scannersAndOffsets =
    new HashMap<Long, RegionNameAndIndex>();

  /**
   * @param sn Name of this mock regionserver
   * @throws IOException 
   * @throws ZooKeeperConnectionException 
   */
  MockRegionServer(final Configuration conf, final ServerName sn)
  throws ZooKeeperConnectionException, IOException {
    this.sn = sn;
    this.conf = conf;
    this.zkw = new ZooKeeperWatcher(conf, sn.toString(), this, true);
  }

  /**
   * Use this method filling the backing data source used by {@link #get(byte[], Get)}
   * @param regionName
   * @param row
   * @param r
   */
  void setGetResult(final byte [] regionName, final byte [] row, final Result r) {
    Map<byte [], Result> value = this.gets.get(regionName);
    if (value == null) {
      // If no value already, create one.  Needs to be treemap because we are
      // using byte array as key.   Not thread safe.
      value = new TreeMap<byte [], Result>(Bytes.BYTES_COMPARATOR);
      this.gets.put(regionName, value);
    }
    value.put(row, r);
  }

  /**
   * Use this method to set what a scanner will reply as we next through
   * @param regionName
   * @param rs
   */
  void setNextResults(final byte [] regionName, final Result [] rs) {
    this.nexts.put(regionName, rs);
  }

  @Override
  public long getProtocolVersion(String protocol, long clientVersion)
      throws IOException {
    // TODO Auto-generated method stub
    return 0;
  }

  @Override
  public ProtocolSignature getProtocolSignature(String protocol,
      long clientVersion, int clientMethodsHash) throws IOException {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public boolean isStopped() {
    // TODO Auto-generated method stub
    return false;
  }

  @Override
  public void abort(String why, Throwable e) {
    throw new RuntimeException(this.sn + ": " + why, e);
  }

  @Override
  public boolean isAborted() {
    return false;
  }

  public long openScanner(byte[] regionName, Scan scan) throws IOException {
    long scannerId = this.random.nextLong();
    this.scannersAndOffsets.put(scannerId, new RegionNameAndIndex(regionName));
    return scannerId;
  }

  public Result next(long scannerId) throws IOException {
    RegionNameAndIndex rnai = this.scannersAndOffsets.get(scannerId);
    int index = rnai.getThenIncrement();
    Result [] results = this.nexts.get(rnai.getRegionName());
    if (results == null) return null;
    return index < results.length? results[index]: null;
  }

  public Result [] next(long scannerId, int numberOfRows) throws IOException {
    // Just return one result whatever they ask for.
    Result r = next(scannerId);
    return r == null? null: new Result [] {r};
  }

  public void close(final long scannerId) throws IOException {
    this.scannersAndOffsets.remove(scannerId);
  }

  @Override
  public void stop(String why) {
    this.zkw.close();
  }

  @Override
  public void addToOnlineRegions(HRegion r) {
    // TODO Auto-generated method stub
  }

  @Override
  public boolean removeFromOnlineRegions(String encodedRegionName, ServerName destination) {
    // TODO Auto-generated method stub
    return false;
  }

  @Override
  public HRegion getFromOnlineRegions(String encodedRegionName) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public Configuration getConfiguration() {
    return this.conf;
  }

  @Override
  public ZooKeeperWatcher getZooKeeper() {
    return this.zkw;
  }

  @Override
  public CatalogTracker getCatalogTracker() {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public ServerName getServerName() {
    return this.sn;
  }

  @Override
  public boolean isStopping() {
    return false;
  }

  @Override
  public HLog getWAL() {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public CompactionRequestor getCompactionRequester() {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public FlushRequester getFlushRequester() {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public RegionServerAccounting getRegionServerAccounting() {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public void postOpenDeployTasks(HRegion r, CatalogTracker ct, boolean daughter)
      throws KeeperException, IOException {
    // TODO Auto-generated method stub
  }

  @Override
  public RpcServer getRpcServer() {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public Map<byte[], Boolean> getRegionsInTransitionInRS() {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public FileSystem getFileSystem() {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public GetResponse get(RpcController controller, GetRequest request)
      throws ServiceException {
    byte[] regionName = request.getRegion().getValue().toByteArray();
    Map<byte [], Result> m = this.gets.get(regionName);
    GetResponse.Builder builder = GetResponse.newBuilder();
    if (m != null) {
      byte[] row = request.getGet().getRow().toByteArray();
      builder.setResult(ProtobufUtil.toResult(m.get(row)));
    }
    return builder.build();
  }

  @Override
  public MutateResponse mutate(RpcController controller, MutateRequest request)
      throws ServiceException {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public ScanResponse scan(RpcController controller, ScanRequest request)
      throws ServiceException {
    ScanResponse.Builder builder = ScanResponse.newBuilder();
    try {
      if (request.hasScan()) {
        byte[] regionName = request.getRegion().getValue().toByteArray();
        builder.setScannerId(openScanner(regionName, null));
        builder.setMoreResults(true);
      }
      else {
        long scannerId = request.getScannerId();
        Result result = next(scannerId);
        if (result != null) {
          builder.addResult(ProtobufUtil.toResult(result));
          builder.setMoreResults(true);
        }
        else {
          builder.setMoreResults(false);
          close(scannerId);
        }
      }
    } catch (IOException ie) {
      throw new ServiceException(ie);
    }
    return builder.build();
  }

  @Override
  public LockRowResponse lockRow(RpcController controller,
      LockRowRequest request) throws ServiceException {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public UnlockRowResponse unlockRow(RpcController controller,
      UnlockRowRequest request) throws ServiceException {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public BulkLoadHFileResponse bulkLoadHFile(RpcController controller,
      BulkLoadHFileRequest request) throws ServiceException {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public ClientProtos.CoprocessorServiceResponse execService(RpcController controller,
      ClientProtos.CoprocessorServiceRequest request) throws ServiceException {
    return null;
  }

  @Override
  public org.apache.hadoop.hbase.protobuf.generated.ClientProtos.MultiResponse multi(
      RpcController controller, MultiRequest request) throws ServiceException {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public GetRegionInfoResponse getRegionInfo(RpcController controller,
      GetRegionInfoRequest request) throws ServiceException {
    GetRegionInfoResponse.Builder builder = GetRegionInfoResponse.newBuilder();
    builder.setRegionInfo(HRegionInfo.convert(HRegionInfo.ROOT_REGIONINFO));
    return builder.build();
  }

  @Override
  public GetStoreFileResponse getStoreFile(RpcController controller,
      GetStoreFileRequest request) throws ServiceException {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public GetOnlineRegionResponse getOnlineRegion(RpcController controller,
      GetOnlineRegionRequest request) throws ServiceException {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public OpenRegionResponse openRegion(RpcController controller,
      OpenRegionRequest request) throws ServiceException {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public CloseRegionResponse closeRegion(RpcController controller,
      CloseRegionRequest request) throws ServiceException {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public FlushRegionResponse flushRegion(RpcController controller,
      FlushRegionRequest request) throws ServiceException {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public SplitRegionResponse splitRegion(RpcController controller,
      SplitRegionRequest request) throws ServiceException {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public CompactRegionResponse compactRegion(RpcController controller,
      CompactRegionRequest request) throws ServiceException {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public ReplicateWALEntryResponse replicateWALEntry(RpcController controller,
      ReplicateWALEntryRequest request) throws ServiceException {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public RollWALWriterResponse rollWALWriter(RpcController controller,
      RollWALWriterRequest request) throws ServiceException {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public GetServerInfoResponse getServerInfo(RpcController controller,
      GetServerInfoRequest request) throws ServiceException {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public StopServerResponse stopServer(RpcController controller,
      StopServerRequest request) throws ServiceException {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public List<HRegion> getOnlineRegions(byte[] tableName) throws IOException {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public Leases getLeases() {
    // TODO Auto-generated method stub
    return null;
  }
}
