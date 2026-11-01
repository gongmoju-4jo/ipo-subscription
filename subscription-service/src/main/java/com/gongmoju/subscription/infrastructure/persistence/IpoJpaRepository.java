package com.gongmoju.subscription.infrastructure.persistence;

import com.gongmoju.subscription.domain.ipo.Ipo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface IpoJpaRepository extends JpaRepository<Ipo, UUID> {
}
