package com.isfam.domain.auth.repository;

import com.isfam.domain.auth.entity.PhoneVerification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

public interface PhoneVerificationRepository extends JpaRepository<PhoneVerification, Long> {

    Optional<PhoneVerification> findByVerificationId(String verificationId);

    /** 재발송 60초 간격 판정 */
    Optional<PhoneVerification> findTopByPhoneNumberOrderByCreatedAtDesc(String phoneNumber);

    /** 발송 횟수 제한 판정 */
    long countByPhoneNumberAndCreatedAtAfter(String phoneNumber, Instant after);
}