package com.example.demo;

import org.json.simple.parser.JSONParser;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@SpringBootApplication
public class UnThreadSafetyParserApplication {

    private static final JSONParser parser = new JSONParser();
    private static final AtomicInteger errorCount = new AtomicInteger(0);
    private static final AtomicInteger successCount = new AtomicInteger(0);

    // 서로 다른 복잡도의 JSON 문자열들
    private static final String[] TEST_JSONS = {
            "{\"key1\":\"value1\", \"key2\":{\"nested\":\"value\"}}",
            "[1,2,{\"array\":\"test\"}]",
            "{\"array\":[1,2,3],\"object\":{\"nested\":\"value\"}}",
            "{\"key\":\"value\"}"
    };

    public static void main(String[] args) throws InterruptedException {
        // 스레드 풀 생성
        ExecutorService executorService = Executors.newFixedThreadPool(10);
        CountDownLatch latch = new CountDownLatch(100);
        List<Future<ParsingResult>> futures = new ArrayList<>();

         MonitoredJSONParser parser1 = new MonitoredJSONParser();

        // 100개의 동시 파싱 작업 실행
        for (int i = 0; i < 100; i++) {
            final int index = i % TEST_JSONS.length;
            Future<ParsingResult> future = executorService.submit(() -> {
                try {
                    // 의도적으로 지연을 주어 경쟁 상태 발생 가능성 증가
                    Thread.sleep(ThreadLocalRandom.current().nextInt(10));

                    // JSON 파싱 시도
                    Object result = parser1.parse(TEST_JSONS[index]);
                    successCount.incrementAndGet();
                    return new ParsingResult(true, null);

                } catch (Exception e) {
                    errorCount.incrementAndGet();
                    return new ParsingResult(false, e);
                } finally {
                    latch.countDown();
                }
            });
            futures.add(future);
        }

        // 모든 작업이 완료될 때까지 대기
        latch.await();
        executorService.shutdown();

        // 결과 분석 및 출력
        System.out.println("테스트 완료!");
        System.out.println("성공 횟수: " + successCount.get());
        System.out.println("실패 횟수: " + errorCount.get());
        System.out.println("\n발생한 에러들:");

        for (Future<ParsingResult> future : futures) {
            try {
                ParsingResult result = future.get();
                if (!result.success && result.error != null) {
                    System.out.println("에러 타입: " + result.error.getClass().getSimpleName());
                    System.out.println("에러 메시지: " + result.error.getMessage());
                    System.out.println("--------------------");
                }
            } catch (Exception e) {
                System.out.println("Future 처리 중 에러: " + e.getMessage());
            }
        }
    }

    // 파싱 결과를 담는 클래스
    private static class ParsingResult {
        final boolean success;
        final Exception error;

        ParsingResult(boolean success, Exception error) {
            this.success = success;
            this.error = error;
        }
    }

    // 더 정밀한 테스트를 위한 메소드
    public static void stressTest() {
        // 파서의 상태를 의도적으로 조작하여 경쟁 상태 유발
        ExecutorService executor = Executors.newFixedThreadPool(2);

        // 첫 번째 스레드: 긴 JSON 파싱
        executor.submit(() -> {
            try {
                String complexJson = "{\"level1\":{\"level2\":{\"level3\":{\"data\":\"value\"}}}}";
                parser.parse(complexJson);
            } catch (org.json.simple.parser.ParseException e) {
                throw new RuntimeException(e);
            }
        });

        // 두 번째 스레드: 파싱 도중 간섭
        executor.submit(() -> {
            try {
                // 첫 번째 스레드가 파싱을 시작하도록 잠시 대기
                Thread.sleep(5);
                String simpleJson = "{\"key\":\"value\"}";
                parser.parse(simpleJson);
            } catch (Exception e) {
                System.out.println("Thread 2 Error: " + e.getMessage());
            }
        });

        executor.shutdown();
    }
}

// 실제 경쟁 상태를 더 잘 관찰하기 위한 모니터링 래퍼
class MonitoredJSONParser extends JSONParser {
    private final AtomicInteger parsingCount = new AtomicInteger(0);

    @Override
    public Object parse(String json) throws org.json.simple.parser.ParseException {
        int currentCount = parsingCount.incrementAndGet();
        try {
            System.out.println("Parsing #" + currentCount + " started by thread: " +
                    Thread.currentThread().getName());
            Object result = super.parse(json);
            System.out.println("Parsing #" + currentCount + " completed successfully");
            return result;
        } catch (org.json.simple.parser.ParseException e) {
            System.out.println("Parsing #" + currentCount + " failed with error: " +
                    e.getMessage());
            throw e;
        } finally {
            parsingCount.decrementAndGet();
        }
    }

}
