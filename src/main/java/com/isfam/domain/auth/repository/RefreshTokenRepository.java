package com.isfam.domain.auth.repository;

import com.isfam.domain.auth.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * 비밀번호 재설정 시 해당 계정의 모든 세션을 끊습니다.
     * 벌크 연산이라 영속성 컨텍스트를 우회하므로 clearAutomatically 를 켰습니다.
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            update RefreshToken t
               set t.revokedAt = :now
             where t.userId = :userId
               and t.revokedAt is null
            """)
    int revokeAllByUserId(@Param("userId") Long userId, @Param("now") Instant now);
}