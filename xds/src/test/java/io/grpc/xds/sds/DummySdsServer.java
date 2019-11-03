/*
 * Copyright 2019 The gRPC Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.xds.sds;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.Strings;
import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.ProtocolStringList;
import io.envoyproxy.envoy.api.v2.DiscoveryRequest;
import io.envoyproxy.envoy.api.v2.DiscoveryResponse;
import io.envoyproxy.envoy.api.v2.auth.Secret;
import io.envoyproxy.envoy.service.discovery.v2.SecretDiscoveryServiceGrpc;
import io.grpc.Server;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;


public class DummySdsServer {
  private static final Logger logger = Logger.getLogger(DummySdsServer.class.getName());

  // key for UDS path in gRPC context metadata map.
  //private static final String UDS_PATH_KEY = ":authority";
  // SecretTypeURL defines the type URL for Envoy secret proto.
  private static final String SECRET_TYPE_URL = "type.googleapis.com/envoy.api.v2.auth.Secret";
  // SecretName defines the type of the secrets to fetch from the SDS server.
  //private static final String SECRET_NAME = "SPKI";
  private final String name;

  String currentVersion;
  String lastRespondedNonce;
  DiscoveryResponse lastResponse;
  List<String> lastResourceNames;
  final SecretDiscoveryServiceImpl discoveryService;

  interface SecretGetter {
    Secret getFor(String name);
  }

  final SecretGetter secretGetter;

  void generateAsyncResponse(String ... names) {
    List<String> nameList = Arrays.asList(names);
    discoveryService.inboundStreamObserver.generateAsyncResponse(nameList);
  }

  class SecretDiscoveryServiceImpl
      extends SecretDiscoveryServiceGrpc.SecretDiscoveryServiceImplBase {

    final long startTime = System.nanoTime();
    SdsInboundStreamObserver inboundStreamObserver;
    DiscoveryRequest lastGoodRequest;

    SecretDiscoveryServiceImpl() { }

    /**
     * This is the inbound observer that sends us a request.
     */
    class SdsInboundStreamObserver implements StreamObserver<DiscoveryRequest> {
      // this is outbound...
      final StreamObserver<DiscoveryResponse> responseObserver;
      ScheduledExecutorService periodicScheduler;
      ScheduledFuture<?> future;

      public SdsInboundStreamObserver(StreamObserver<DiscoveryResponse> responseObserver) {
        this.responseObserver = responseObserver;
        //setupPeriodicResponses();
      }

      private void setupPeriodicResponses() {
        periodicScheduler = Executors.newSingleThreadScheduledExecutor();
        future = periodicScheduler.scheduleAtFixedRate(new Runnable() {
          @Override
          public void run() {
            // generate a response every 30 seconds
            try {
              if (lastGoodRequest != null) {
                ProtocolStringList resourceNames = lastGoodRequest.getResourceNamesList();
                ArrayList<String> subset = new ArrayList<>();
                // select a subset of resource names
                for (Iterator<String> it = resourceNames.iterator(); it.hasNext(); ) {
                  String cur = it.next();
                  long randVal = Math.round(Math.random());
                  if (randVal == 1L) {
                    subset.add(cur);
                  }
                }
                generateAsyncResponse(subset);
              }
            } catch (Throwable t) {
              logger.log(Level.SEVERE, "run", t);
            }

          }
        }, 30L, 30L, TimeUnit.SECONDS);
      }

      private void generateAsyncResponse(List<String> nameList) {
        if (!nameList.isEmpty()) {
          SdsInboundStreamObserver.this.responseObserver.onNext(
                  buildResponse(currentVersion, lastRespondedNonce, nameList, true));
        }
      }

      @Override
      public void onNext(DiscoveryRequest discoveryRequest) {
        DiscoveryResponse discoveryResponse = buildResponse(discoveryRequest);
        if (discoveryResponse != null) {
          lastGoodRequest = discoveryRequest;
          responseObserver.onNext(discoveryResponse);
        }
      }

      @Override
      public void onError(Throwable t) {
        logger.log(Level.SEVERE, "onError", t);
      }

      @Override
      public void onCompleted() {
        responseObserver.onCompleted();
      }
    }

    // bidi streaming
    @Override
    public StreamObserver<DiscoveryRequest> streamSecrets(
        StreamObserver<DiscoveryResponse> responseObserver) {
      inboundStreamObserver = new SdsInboundStreamObserver(responseObserver);
      return inboundStreamObserver;
    }

    // unary call
    @Override
    public void fetchSecrets(DiscoveryRequest request,
                             StreamObserver<DiscoveryResponse> responseObserver) {

      DiscoveryResponse response = buildResponse(request);
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }

    private DiscoveryResponse buildResponse(DiscoveryRequest request) {
      String requestVersion = request.getVersionInfo();
      String requestNonce = request.getResponseNonce();
      ProtocolStringList resourceNames = request.getResourceNamesList();
      return buildResponse(requestVersion, requestNonce, resourceNames, false);
    }

    private DiscoveryResponse buildResponse(String requestVersion, String requestNonce,
                                            List<String> resourceNames, boolean forcedAsync) {
      // for stale version or nonce don't send a response
      if (!Strings.isNullOrEmpty(requestVersion) && !requestVersion.equals(currentVersion)) {
        logger.info("Stale version received: " + requestVersion);
        return null;
      }
      if (!Strings.isNullOrEmpty(requestNonce) && !requestNonce.equals(lastRespondedNonce)) {
        logger.info("Stale nonce received: " + requestNonce);
        return null;
      }
      // check if any new resources are being requested...
      if (!forcedAsync && !isStrictSuperset(resourceNames, lastResourceNames)) {
        logger.info("No new resources requested: " + resourceNames);
        return null;
      }

      final String version = "" + ((System.nanoTime() - startTime) / 1000000L);

      DiscoveryResponse.Builder responseBuilder = DiscoveryResponse.newBuilder()
              .setVersionInfo(version)
              .setNonce(getAndSaveNonce())
              .setTypeUrl(SECRET_TYPE_URL);

      for (String resourceName : resourceNames) {
        buildAndAddResource(responseBuilder, resourceName);
      }
      DiscoveryResponse response = responseBuilder
              .build();
      currentVersion = version;
      lastResponse = response;
      lastResourceNames = resourceNames;
      return response;
    }

    private void buildAndAddResource(
        DiscoveryResponse.Builder responseBuilder, String resourceName) {

      Secret secret = secretGetter.getFor(resourceName);
      ByteString data = secret.toByteString();
      Any anyValue = Any.newBuilder().setTypeUrl(SECRET_TYPE_URL).setValue(data).build();
      responseBuilder.addResources(anyValue);
    }
  }

  private boolean isStrictSuperset(List<String> resourceNames, List<String> lastResourceNames) {
    if (resourceNames == null || resourceNames.isEmpty()) {
      return false;
    }
    if (lastResourceNames == null || lastResourceNames.isEmpty()) {
      return true;
    }
    return resourceNames.containsAll(lastResourceNames)
            && !lastResourceNames.containsAll(resourceNames);
  }

  void runServer() throws IOException {
    Server unused =
            InProcessServerBuilder.forName(name)
                    .addService(discoveryService).directExecutor().build().start();
  }

  DummySdsServer(String name, SecretGetter secretGetter) {
    checkNotNull(secretGetter, "secretGetter");
    this.name = name;
    this.secretGetter = secretGetter;
    this.discoveryService = new SecretDiscoveryServiceImpl();
  }

  private String getAndSaveNonce() {
    lastRespondedNonce = Long.toHexString(System.currentTimeMillis());
    return lastRespondedNonce;
  }

}
