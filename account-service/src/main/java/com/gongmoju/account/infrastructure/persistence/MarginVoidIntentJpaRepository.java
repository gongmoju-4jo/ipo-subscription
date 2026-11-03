package com.gongmoju.account.infrastructure.persistence;

import com.gongmoju.account.domain.margin.MarginVoidIntent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface MarginVoidIntentJpaRepository extends JpaRepository<MarginVoidIntent, UUID> {
}
