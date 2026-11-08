package dev.rehearsal;

import jakarta.persistence.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.*;

@Entity
@Table(name="projects")
class Project {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    @Column(nullable=false) long ownerId;
    @Column(nullable=false,length=120) String name;
    @Column(nullable=false,length=2000) String description;
    @Column(nullable=false,length=20) String postgresVersion;
    @Column(nullable=false) Instant createdAt = Instant.now();

    protected Project() {}
}

interface Projects extends JpaRepository<Project,Long> {
    Optional<Project> findByIdAndOwnerId(long id, long ownerId);
    List<Project> findByOwnerIdOrderByCreatedAtDesc(long ownerId);
}