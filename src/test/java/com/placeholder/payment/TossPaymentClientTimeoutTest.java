package com.placeholder.payment;

import com.placeholder.domain.payment.client.TossPaymentClientImpl;
import com.placeholder.global.exception.custom.PaymentCancelFailedException;
import com.placeholder.global.exception.custom.PaymentConfirmFailedException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 토스 호출 타임아웃 검증 (ADR-018).
 *
 * <p>승인 호출은 트랜잭션 밖이라 DB 커넥션은 잡지 않지만, 응답이 오지 않으면 요청 스레드가 계속
 * 붙잡힌다. 타임아웃이 없으면 토스 지연이 좌석 hold 등 무관한 경로까지 스레드 고갈로 전파된다.
 *
 * <p>연결만 받고 <b>영원히 응답하지 않는</b> 소켓 서버를 띄워, read timeout이 실제로 발동해
 * 유한 시간 안에 {@link PaymentConfirmFailedException}으로 끝나는지 확인한다.
 * (스프링 컨텍스트가 필요 없는 순수 클라이언트 검증이라 통합 테스트 베이스를 쓰지 않는다.)
 */
class TossPaymentClientTimeoutTest {

    private static ServerSocket serverSocket;
    private static final AtomicBoolean running = new AtomicBoolean(true);
    private static final List<Socket> accepted = new ArrayList<>();

    @BeforeAll
    static void startSilentServer() throws IOException {
        serverSocket = new ServerSocket(0);
        Thread acceptor = new Thread(() -> {
            while (running.get()) {
                try {
                    // 수락만 하고 응답은 절대 쓰지 않는다 → 클라이언트는 read 대기에 걸린다.
                    accepted.add(serverSocket.accept());
                } catch (IOException e) {
                    return; // 서버 종료
                }
            }
        });
        acceptor.setDaemon(true);
        acceptor.start();
    }

    @AfterAll
    static void stopSilentServer() throws IOException {
        running.set(false);
        for (Socket s : accepted) {
            s.close();
        }
        serverSocket.close();
    }

    @Test
    @DisplayName("응답 없는 서버: read timeout이 발동해 유한 시간 내 실패 (스레드 무한 점유 없음)")
    void confirm_timesOut_insteadOfHangingForever() {
        String baseUrl = "http://localhost:" + serverSocket.getLocalPort();
        // read timeout 1초로 좁혀 검증 시간을 줄인다(운영 기본값은 application.yml의 5000ms).
        TossPaymentClientImpl client = new TossPaymentClientImpl(baseUrl, "test_secret", 1_000, 1_000);

        long startedAt = System.currentTimeMillis();
        assertThatThrownBy(() -> client.confirm("pk_1", "order_1", 10_000))
                .isInstanceOf(PaymentConfirmFailedException.class);
        long elapsed = System.currentTimeMillis() - startedAt;

        // 하한: 설정한 read timeout만큼은 실제로 기다렸어야 한다. 이게 없으면 연결 거부처럼
        //       "즉시 실패"하는 경우에도 테스트가 통과해 타임아웃 동작을 증명하지 못한다.
        // 상한: 타임아웃이 없으면 영원히 매달리므로, 상한 위반은 곧 미설정을 뜻한다.
        assertThat(elapsed).isGreaterThanOrEqualTo(900).isLessThan(10_000);
    }

    @Test
    @DisplayName("타임아웃이 전송 단계와 겹쳐도 도메인 예외로 번역된다 (CancellationException 누출 없음)")
    void timeoutDuringSend_stillTranslatedToDomainException() {
        String baseUrl = "http://localhost:" + serverSocket.getLocalPort();

        // 읽기 타임아웃을 극단적으로 좁혀 '요청 전송 중 취소'를 강제한다. 이 구간에서 JDK HttpClient는
        // CompletableFuture.cancel()로 중단하는데, 그때 나오는 CancellationException은
        // RestClientException이 아니다 — RestClientException만 잡으면 그대로 새어 나가고,
        // confirm의 실패 처리(markFailed)가 건너뛰어져 주문이 READY로 남는다.
        // 경합이라 매번 발현하지 않으므로 여러 번 시도해 구간을 확실히 통과시킨다.
        for (int i = 0; i < 30; i++) {
            TossPaymentClientImpl client = new TossPaymentClientImpl(baseUrl, "test_secret", 1_000, 1);

            assertThatThrownBy(() -> client.confirm("pk_1", "order_1", 10_000))
                    .isInstanceOf(PaymentConfirmFailedException.class);
            assertThatThrownBy(() -> client.cancel("pk_1", "사유", 10_000, "idem-1"))
                    .isInstanceOf(PaymentCancelFailedException.class);
        }
    }
}
