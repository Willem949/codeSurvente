package com.wk.flashsale.event;

import org.springframework.data.jpa.repository.JpaRepository;

public interface VersionedEventRepository extends JpaRepository<VersionedEvent, Long> {
}
