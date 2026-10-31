package com.isfam.domain.device.repository;

import com.isfam.domain.device.entity.UserSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface UserSettingsRepository extends JpaRepository<UserSettings, Long> {

    /**
     * 기본값 행을 만듭니다. <b>이미 있으면 아무것도 하지 않습니다.</b>
     *
     * <p>조회 후 저장으로 만들면 동시 요청 두 개가 둘 다 "없음"을 보고 INSERT 해서 한쪽이
     * PK 위반으로 터지고, 그 트랜잭션은 통째로 롤백됩니다. {@code ON CONFLICT DO NOTHING} 이면
     * 진 쪽도 조용히 넘어가고 바로 다음 조회에서 이긴 쪽 행을 읽습니다.
     *
     * <p>토글 값은 넣지 않고 <b>컬럼 DEFAULT</b> 를 씁니다 — {@code UserSettings} 의 {@code @ColumnDefault}
     * 와 {@code db/notification-settings-1.sql} 이 같은 값을 정의합니다.
     *
     * <p>가입(AuthService)과 설정 변경(SettingsService) 두 곳에서 부릅니다. 기존 사용자는
     * 마이그레이션 SQL 이 채우므로, 설정 변경 쪽은 그 사이 빈틈을 막는 방어입니다.
     *
     * @return 새로 만들었으면 1, 이미 있었으면 0
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            insert into user_settings (user_id, created_at, updated_at)
            values (:userId, :now, :now)
            on conflict (user_id) do nothing
            """, nativeQuery = true)
    int insertDefaultsIfAbsent(@Param("userId") Long userId, @Param("now") Instant now);

    /**
     * 회원 탈퇴 시 삭제. 계정이 사라지면 설정을 들고 있을 이유가 없습니다.
     * 호출은 {@code WithdrawalService} 에서만 — {@code data_deletion_logs} 에 건수가 남아야 합니다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from UserSettings s where s.userId = :userId")
    int deleteByUserId(@Param("userId") Long userId);
}