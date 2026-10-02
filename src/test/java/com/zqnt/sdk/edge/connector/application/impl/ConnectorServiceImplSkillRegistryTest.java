package com.zqnt.sdk.edge.connector.application.impl;

import com.zqnt.utils.common.proto.ErrorCode;
import com.zqnt.utils.common.proto.GlobalErrorMessage;
import com.zqnt.utils.connector.proto.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** In-process gRPC coverage for the Skill Registry methods added to {@link ConnectorServiceImpl}
 * (spec §32 — propagating the capability/skill protocol into the edge SDK). Mirrors
 * {@code LiveDataServiceImplTest}'s in-process server pattern. */
class ConnectorServiceImplSkillRegistryTest {

    private Server server;
    private ManagedChannel channel;
    private ConnectorServiceImpl service;

    @AfterEach
    void tearDown() throws InterruptedException {
        if (channel != null) channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        if (server != null) server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void observesASkillContractAndReturnsTheStoredResult() throws Exception {
        var stored = SkillContractProtoDTO.newBuilder().setId("row-1").setCommandId("acme.custom_scan").build();
        start(new FakeConnectorService() {
            @Override public void observeSkillContract(UpsertSkillContractRequest request,
                    StreamObserver<SkillContractResponse> observer) {
                assertEquals("acme.custom_scan", request.getContract().getCommandId());
                respond(observer, SkillContractResponse.newBuilder().setContract(stored).build());
            }
        });

        var result = service.observeSkillContract(SkillContractProtoDTO.newBuilder()
                .setCommandId("acme.custom_scan").build()).get(5, TimeUnit.SECONDS);

        assertEquals("row-1", result.getId());
    }

    @Test
    void observeSkillContractReturnsNullWhenTheServerReportsAnError() throws Exception {
        start(new FakeConnectorService() {
            @Override public void observeSkillContract(UpsertSkillContractRequest request,
                    StreamObserver<SkillContractResponse> observer) {
                respond(observer, SkillContractResponse.newBuilder().setHasErrors(true)
                        .setError(GlobalErrorMessage.newBuilder().setErrorCode(ErrorCode.ERROR_CODE_CLIENT)
                                .setErrorMessage("rejected")).build());
            }
        });

        var result = service.observeSkillContract(SkillContractProtoDTO.newBuilder()
                .setCommandId("acme.custom_scan").build()).get(5, TimeUnit.SECONDS);

        assertNull(result);
    }

    @Test
    void listsSkillContractsFilteredByCommandId() throws Exception {
        start(new FakeConnectorService() {
            @Override public void listSkillContracts(ListSkillContractsRequest request,
                    StreamObserver<SkillContractListResponse> observer) {
                assertEquals("flight.takeoff", request.getCommandId());
                respond(observer, SkillContractListResponse.newBuilder()
                        .addContracts(SkillContractProtoDTO.newBuilder().setCommandId("flight.takeoff")).build());
            }
        });

        var result = service.listSkillContracts(null, "flight.takeoff").get(5, TimeUnit.SECONDS);

        assertEquals(1, result.size());
        assertEquals("flight.takeoff", result.get(0).getCommandId());
    }

    @Test
    void listSkillContractsReturnsEmptyListWhenTheServerReportsAnError() throws Exception {
        start(new FakeConnectorService() {
            @Override public void listSkillContracts(ListSkillContractsRequest request,
                    StreamObserver<SkillContractListResponse> observer) {
                respond(observer, SkillContractListResponse.newBuilder().setHasErrors(true).build());
            }
        });

        var result = service.listSkillContracts(SkillContractStatus.SKILL_CONTRACT_STATUS_ACTIVE, null)
                .get(5, TimeUnit.SECONDS);

        assertTrue(result.isEmpty());
    }

    @Test
    void setsSkillContractStatus() throws Exception {
        start(new FakeConnectorService() {
            @Override public void setSkillContractStatus(SetSkillContractStatusRequest request,
                    StreamObserver<SkillContractResponse> observer) {
                assertEquals("row-1", request.getId());
                assertEquals(SkillContractStatus.SKILL_CONTRACT_STATUS_DEPRECATED, request.getStatus());
                respond(observer, SkillContractResponse.newBuilder()
                        .setContract(SkillContractProtoDTO.newBuilder().setId("row-1")
                                .setStatus(SkillContractStatus.SKILL_CONTRACT_STATUS_DEPRECATED)).build());
            }
        });

        var result = service.setSkillContractStatus("row-1", SkillContractStatus.SKILL_CONTRACT_STATUS_DEPRECATED)
                .get(5, TimeUnit.SECONDS);

        assertEquals(SkillContractStatus.SKILL_CONTRACT_STATUS_DEPRECATED, result.getStatus());
    }

    @Test
    void setsSkillContractPermissionsAsAFullReplacement() throws Exception {
        start(new FakeConnectorService() {
            @Override public void setSkillContractPermissions(SetSkillContractPermissionsRequest request,
                    StreamObserver<SkillContractResponse> observer) {
                assertEquals(List.of("mission.launch", "role:pilot"), request.getRequiredPermissionsList());
                respond(observer, SkillContractResponse.newBuilder()
                        .setContract(SkillContractProtoDTO.newBuilder().setId("row-1")
                                .addAllRequiredPermissions(request.getRequiredPermissionsList())).build());
            }
        });

        var result = service.setSkillContractPermissions("row-1", List.of("mission.launch", "role:pilot"))
                .get(5, TimeUnit.SECONDS);

        assertEquals(List.of("mission.launch", "role:pilot"), result.getRequiredPermissionsList());
    }

    private void start(FakeConnectorService implementation) throws IOException {
        server = ServerBuilder.forPort(0).addService(implementation).build().start();
        channel = ManagedChannelBuilder.forAddress("127.0.0.1", server.getPort()).usePlaintext().build();
        service = new ConnectorServiceImpl(null, ConnectorServiceGrpc.newStub(channel));
    }

    private static <T> void respond(StreamObserver<T> observer, T value) {
        observer.onNext(value);
        observer.onCompleted();
    }

    private static class FakeConnectorService extends ConnectorServiceGrpc.ConnectorServiceImplBase {
    }
}
