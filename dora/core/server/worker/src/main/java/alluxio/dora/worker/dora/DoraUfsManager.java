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

package alluxio.dora.worker.dora;

import alluxio.dora.conf.Configuration;
import alluxio.dora.underfs.AbstractUfsManager;
import alluxio.dora.underfs.UnderFileSystem;
import alluxio.dora.util.network.NetworkAddressUtils;

import java.io.IOException;

/**
 * Dora UFS manager.
 */
public class DoraUfsManager extends AbstractUfsManager {
  @Override
  protected void connectUfs(UnderFileSystem fs) throws IOException {
    fs.connectFromWorker(
        NetworkAddressUtils.getConnectHost(NetworkAddressUtils.ServiceType.WORKER_RPC,
            Configuration.global()));
  }
}