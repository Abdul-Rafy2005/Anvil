package com.anvil.job.repository;

import com.anvil.job.domain.JobAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface JobAttemptRepository extends JpaRepository<JobAttempt, UUID> {
}
