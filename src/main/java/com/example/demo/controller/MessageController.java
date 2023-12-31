package com.example.demo.controller;

import com.amazonaws.services.sns.AmazonSNS;
import com.amazonaws.services.sns.AmazonSNSClientBuilder;
import com.amazonaws.services.sns.model.PublishRequest;
import com.amazonaws.services.sns.model.PublishResult;
import com.example.demo.dto.MessageDto;
import com.example.demo.dto.ReceiptHandleDto;
import com.example.demo.repository.UserRepository;
import com.example.demo.service.SqsService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/messages")
@Tag(name = "MessageController", description = "SQS 큐 대기열에 있는 메시지 조회")
public class MessageController {

    private final SqsService sqsReceiverService;
    private final AmazonSNS snsClient;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    public MessageController(SqsService sqsReceiverService) {
        this.sqsReceiverService = sqsReceiverService;
        this.snsClient = AmazonSNSClientBuilder.defaultClient();
    }


    @GetMapping
    public List<MessageDto> getMessages() {
        return sqsReceiverService.receiveMessages();
    }

    @PostMapping("/completeAndNotify")
    @Tag(name = "MessageController", description = "모바일 푸시 알림 전송 후 SQS 큐 대기열에서 삭제")
    public ResponseEntity<Map<String, String>> completeAndNotify(@RequestParam String customerId, @RequestBody ReceiptHandleDto receiptHandleDto) {
        // 1. 사용자에게 알림
        String endpointArn = userRepository.findEndpointArnByCustomerId(customerId);

        if (endpointArn == null || endpointArn.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "Endpoint ARN not found for the user."));
        }

        String messageTitle = "패스트오더";  // 알림 제목 추가
        String messageText = "상품 준비가 완료되었습니다. 픽업대를 찾아주세요!";

        // FCM 페이로드 형식으로 메시지 설정
        String formattedMessage = "{"
                + "\"default\": \"" + messageText + "\","
                + "\"GCM\": \"{ \\\"notification\\\": { \\\"title\\\": \\\"" + messageTitle + "\\\", \\\"body\\\": \\\"" + messageText + "\\\" } }\""
                + "}";

        PublishRequest publishRequest = new PublishRequest()
                .withMessageStructure("json") // 페이로드 형식
                .withMessage(formattedMessage)
                .withTargetArn(endpointArn);

        PublishResult publishResult;
        try {
            publishResult = snsClient.publish(publishRequest);
        } catch (Exception e) {
            // SNS 전송 중 오류 발생 시
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "메시지 삭제에 실패하였습니다. \n페이지를 새로고침해주세요."));
        }

        // 2. 메시지 삭제
        sqsReceiverService.deleteMessage(receiptHandleDto.getReceiptHandle());

        Map<String, String> response = new HashMap<>();
        response.put("message", "메시지가 성공적으로 처리되었습니다.");
        response.put("snsMessageId", publishResult.getMessageId());

        return ResponseEntity.ok().body(response);
    }


}
