package com.isfam.domain.auth.repository;

import com.isfam.domain.auth.entity.UserAgreement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UserAgreementRepository extends JpaRepository<UserAgreement, Long> {

    List<UserAgreement> findByUserId(Long userId);
}