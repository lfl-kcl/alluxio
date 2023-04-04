/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.dora;

import alluxio.dora.exception.status.UnavailableException;
import alluxio.dora.master.MasterClientContext;
import alluxio.dora.master.MasterInquireClient;
import alluxio.dora.retry.RetryPolicy;

import java.net.InetSocketAddress;
import java.util.function.Supplier;
import javax.annotation.concurrent.ThreadSafe;

/**
 * The base class for job master clients.
 */
@ThreadSafe
public abstract class AbstractJobMasterClient extends AbstractMasterClient {
  /** Address to load configuration, which may differ from {@code mAddress}. */
  protected InetSocketAddress mConfAddress;

  /** Client for determining configuration RPC address,
   * which may be different from target address. */
  private final MasterInquireClient mConfMasterInquireClient;

  /**
   * Creates a new master client base.
   *
   * @param clientConf master client configuration
   */
  public AbstractJobMasterClient(MasterClientContext clientConf) {
    super(clientConf);
    mConfMasterInquireClient = clientConf.getConfMasterInquireClient();
  }

  /**
   * Creates a new master client base.
   *
   * @param clientConf master client configuration
   * @param retryPolicySupplier retry policy to use
   */
  public AbstractJobMasterClient(MasterClientContext clientConf,
                              Supplier<RetryPolicy> retryPolicySupplier) {
    super(clientConf, retryPolicySupplier);
    mConfMasterInquireClient = clientConf.getConfMasterInquireClient();
  }

  @Override
  public synchronized InetSocketAddress getConfAddress() throws UnavailableException {
    return mConfMasterInquireClient.getPrimaryRpcAddress();
  }
}