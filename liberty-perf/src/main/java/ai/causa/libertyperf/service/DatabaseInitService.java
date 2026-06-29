package ai.causa.libertyperf.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import ai.causa.libertyperf.repository.AccountRepository;
import ai.causa.libertyperf.repository.BookingRepository;

import java.util.logging.Logger;

/**
 * Initializes the database schema and seed data on application startup.
 *
 * <p>Uses {@code @Observes @Initialized(ApplicationScoped.class)} to run
 * eagerly at CDI startup, before any REST requests arrive.
 */
@ApplicationScoped
public class DatabaseInitService {

    private static final Logger LOG = Logger.getLogger(DatabaseInitService.class.getName());

    @Inject
    AccountRepository accountRepository;

    @Inject
    BookingRepository bookingRepository;

    /**
     * Called by CDI container when the ApplicationScoped context is initialized —
     * i.e. at application startup, before any requests are served.
     */
    public void init(@Observes @Initialized(ApplicationScoped.class) Object event) {
        LOG.info("Initialising database schema...");
        accountRepository.ensureSchema();
        bookingRepository.ensureSchema();
        LOG.info("Database schema initialisation complete.");
    }
}
