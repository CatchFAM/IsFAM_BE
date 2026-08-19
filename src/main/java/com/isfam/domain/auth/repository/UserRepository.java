package com.isfam.domain.auth.repository;

import com.isfam.domain.auth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 조회 조건에 항상 {@code deletedAt IS NULL} 이 붙습니다.
 * 탈퇴 계정은 번호가 해시로 치환돼 있어 실제로 매칭될 일은 없지만,
 * 조건을 빼면 탈퇴자가 로그인되는 경로가 생깁니다.
 */
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByPhoneNumberAndDeletedAtIsNull(String phoneNumber);

    Optional<User> findByIdAndDeletedAtIsNull(Long id);

    boolean existsByPhoneNumberAndDeletedAtIsNull(String phoneNumber);
}