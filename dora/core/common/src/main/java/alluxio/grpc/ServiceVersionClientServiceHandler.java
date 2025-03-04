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

package alluxio.grpc;

import alluxio.Constants;
import alluxio.annotation.SuppressFBWarnings;
import alluxio.conf.Configuration;
import alluxio.conf.PropertyKey;

import com.google.common.collect.ImmutableSet;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/**
 * This class is a gRPC handler that serves Alluxio service versions.
 */
public final class ServiceVersionClientServiceHandler
    extends ServiceVersionClientServiceGrpc.ServiceVersionClientServiceImplBase {
  /** Set of services that are going to be recognized by this versioning service. */
  private final Set<ServiceType> mServices;
  @Nullable private final Supplier<NodeState> mNodeStateSupplier;
  private final boolean mStandbyRpcEnabled =
      Configuration.getBoolean(PropertyKey.STANDBY_MASTER_GRPC_ENABLED);

  /**
   * Creates service version handler that allows given services.
   * @param services services to allow
   * @param nodeStateSupplier the supplier to get the node state
   */
  public ServiceVersionClientServiceHandler(
      Set<ServiceType> services, @Nullable Supplier<NodeState> nodeStateSupplier) {
    mServices = ImmutableSet.copyOf(Objects.requireNonNull(services, "services is null"));
    mNodeStateSupplier = nodeStateSupplier;
  }

  @Override
  @SuppressFBWarnings(value = "DB_DUPLICATE_SWITCH_CLAUSES")
  public void getServiceVersion(GetServiceVersionPRequest request,
      StreamObserver<GetServiceVersionPResponse> responseObserver) {
    // getAllowedOnStandbyMasters() is defaulted to false
    if (!request.getAllowedOnStandbyMasters() && mStandbyRpcEnabled
        && mNodeStateSupplier != null && mNodeStateSupplier.get() == NodeState.STANDBY) {
      responseObserver.onError(Status.UNAVAILABLE
          .withDescription("GetServiceVersion is not supported on standby master")
          .asException());
      return;
    }

    ServiceType serviceType = request.getServiceType();
    if (serviceType != ServiceType.UNKNOWN_SERVICE && !mServices.contains(serviceType)) {
      responseObserver.onError(Status.NOT_FOUND
          .withDescription(String.format("Service %s is not found.", serviceType.name()))
          .asException());
      return;
    }

    long serviceVersion;
    switch (serviceType) {
      case FILE_SYSTEM_MASTER_CLIENT_SERVICE:
        serviceVersion = Constants.FILE_SYSTEM_MASTER_CLIENT_SERVICE_VERSION;
        break;
      case FILE_SYSTEM_MASTER_WORKER_SERVICE:
        serviceVersion = Constants.FILE_SYSTEM_MASTER_WORKER_SERVICE_VERSION;
        break;
      case FILE_SYSTEM_MASTER_JOB_SERVICE:
        serviceVersion = Constants.FILE_SYSTEM_MASTER_JOB_SERVICE_VERSION;
        break;
      case BLOCK_MASTER_CLIENT_SERVICE:
        serviceVersion = Constants.BLOCK_MASTER_CLIENT_SERVICE_VERSION;
        break;
      case BLOCK_MASTER_WORKER_SERVICE:
        serviceVersion = Constants.BLOCK_MASTER_WORKER_SERVICE_VERSION;
        break;
      case BLOCK_WORKER_CLIENT_SERVICE:
        serviceVersion = Constants.BLOCK_WORKER_CLIENT_SERVICE_VERSION;
        break;
      case META_MASTER_CONFIG_SERVICE:
        serviceVersion = Constants.META_MASTER_CONFIG_SERVICE_VERSION;
        break;
      case META_MASTER_CLIENT_SERVICE:
        serviceVersion = Constants.META_MASTER_CLIENT_SERVICE_VERSION;
        break;
      case META_MASTER_MASTER_SERVICE:
        serviceVersion = Constants.META_MASTER_MASTER_SERVICE_VERSION;
        break;
      case METRICS_MASTER_CLIENT_SERVICE:
        serviceVersion = Constants.METRICS_MASTER_CLIENT_SERVICE_VERSION;
        break;
      case JOB_MASTER_CLIENT_SERVICE:
        serviceVersion = Constants.JOB_MASTER_CLIENT_SERVICE_VERSION;
        break;
      case JOB_MASTER_WORKER_SERVICE:
        serviceVersion = Constants.JOB_MASTER_WORKER_SERVICE_VERSION;
        break;
      case JOURNAL_MASTER_CLIENT_SERVICE:
        serviceVersion = Constants.JOURNAL_MASTER_CLIENT_SERVICE_VERSION;
        break;
      case TABLE_MASTER_CLIENT_SERVICE:
        serviceVersion = Constants.TABLE_MASTER_CLIENT_SERVICE_VERSION;
        break;
      case RAFT_JOURNAL_SERVICE:
        serviceVersion = Constants.RAFT_JOURNAL_SERVICE_VERSION;
        break;
      case SECURITY_SERVER_CLIENT_SERVICE:
        serviceVersion = Constants.SECURITY_SERVER_VERSION;
        break;
      default:
        serviceVersion = Constants.UNKNOWN_SERVICE_VERSION;
        break;
    }
    responseObserver
        .onNext(GetServiceVersionPResponse.newBuilder().setVersion(serviceVersion).build());
    responseObserver.onCompleted();
  }
}
