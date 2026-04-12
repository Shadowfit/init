package com.shadowfit.global.grpc;

import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import com.shadowfit.global.grpc.UserServiceGrpc; // 자동 생성된 클래스 임포트
import com.shadowfit.global.grpc.UserResponse;   // 자동 생성된 클래스 임포트
import com.shadowfit.global.grpc.UserRequest;    // 자동 생성된 클래스 임포트

@GrpcService
public class UserGrpcService extends UserServiceGrpc.UserServiceImplBase {

    @Override
    public void getUserInfo(UserRequest request, StreamObserver<UserResponse> responseObserver) {
        // 테스트용 간단 응답
        UserResponse response = UserResponse.newBuilder()
                .setNickname("ShadowFit_Admin")
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
}