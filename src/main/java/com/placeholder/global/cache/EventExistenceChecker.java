package com.placeholder.global.cache;

import com.placeholder.domain.event.repository.EventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

/**
 * 이벤트 존재 여부를 Caffeine 캐시로 제공한다.
 *
 * <p>enter/status 요청마다 {@code existsById}가 MySQL로 직행하는 것을 방지한다.
 * 이벤트 id는 생성 후 변경·삭제되지 않는 근-불변 데이터라 TTL 60초 캐시가 안전하다.
 * 존재하지 않는 id는 캐시하지 않아(unless) 런타임에 새로 생성된 이벤트가 즉시 인식된다.
 */
@Component
@RequiredArgsConstructor
public class EventExistenceChecker {

    private final EventRepository eventRepository;

    @Cacheable(value = "eventExists", unless = "#result == false")
    public boolean exists(Long eventId) {
        return eventRepository.existsById(eventId);
    }
}
