package dev.rehearsal;

import jakarta.persistence.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

@Entity
@Table(name="accounts")
class Account {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    @Column(nullable=false,unique=true,length=254) String email;
    @Column(nullable=false,length=100) String passwordHash;

    protected Account() {}
    Account(String email, String passwordHash) {
        this.email = email;
        this.passwordHash = passwordHash;
    }
}

interface Accounts extends JpaRepository<Account,Long> {
    Optional<Account> findByEmail(String email);
}