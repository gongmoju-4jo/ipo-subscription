package com.gongmoju.subscription.domain.ipo;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;
import java.util.UUID;

// 공모주 저장소. 구현은 infrastructure/persistence (JPA)
public interface IpoRepository {

    Page<Ipo> findAll(Pageable pageable);

    Optional<Ipo> findById(UUID ipoId);
}
