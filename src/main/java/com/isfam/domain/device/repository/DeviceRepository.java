package com.isfam.domain.device.repository;

import com.isfam.domain.device.entity.Device;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DeviceRepository extends JpaRepository<Device, Long> {

    /** POST /devices 중복 판정 키 */
    Optional<Device> findByUserIdAndPushToken(Long userId, String pushToken);

    /** X-Device-Id 소유권 확인 — 남의 단말이면 비어서 돌아옵니다 */
    Optional<Device> findByIdAndUserId(Long id, Long userId);

    /** Phase 5 동기화 푸시 대상 조회에서 사용 */
    List<Device> findByUserId(Long userId);
}