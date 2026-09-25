package com.zqnt.sdk.edge.connector.application.impl;

import com.zqnt.utils.connector.proto.ConnectorServiceGrpc;
import com.zqnt.utils.media.proto.MediaFileProtoDTO;
import com.zqnt.utils.media.proto.MediaFileStatus;
import com.zqnt.utils.media.proto.RegisterMediaFileRequest;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** In-process gRPC coverage for {@link ConnectorServiceImpl#registerMediaFile}. */
class ConnectorServiceImplMediaTest {

    private Server server;
    private ManagedChannel channel;
    private ConnectorServiceImpl service;

    @AfterEach
    void tearDown() throws InterruptedException {
        if (channel != null) channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        if (server != null) server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void registersAnUploadedFileAndFillsInTheRequestBase() throws Exception {
        start(new ConnectorServiceGrpc.ConnectorServiceImplBase() {
            @Override public void registerMediaFile(RegisterMediaFileRequest request,
                    StreamObserver<MediaFileProtoDTO> observer) {
                assertEquals("dji/DOCK-1/photo.jpg", request.getSourceObjectKey());
                assertEquals("DOCK-1", request.getBase().getSn());
                assertFalse(request.getBase().getTid().isBlank());
                observer.onNext(MediaFileProtoDTO.newBuilder().setId("media-1")
                        .setStatus(MediaFileStatus.MEDIA_FILE_STATUS_STORED).build());
                observer.onCompleted();
            }
        });

        var stored = service.registerMediaFile(RegisterMediaFileRequest.newBuilder()
                .setAssetSn("DOCK-1").setSourceBucket("zqnt-inbox")
                .setSourceObjectKey("dji/DOCK-1/photo.jpg").setFileName("photo.jpg").build())
                .get(5, TimeUnit.SECONDS);

        assertEquals("media-1", stored.getId());
    }

    private void start(ConnectorServiceGrpc.ConnectorServiceImplBase implementation) throws IOException {
        server = ServerBuilder.forPort(0).addService(implementation).build().start();
        channel = ManagedChannelBuilder.forAddress("127.0.0.1", server.getPort()).usePlaintext().build();
        service = new ConnectorServiceImpl(null, ConnectorServiceGrpc.newStub(channel));
    }
}
