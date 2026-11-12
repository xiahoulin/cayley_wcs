import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.flywaydb.core.Flyway;

/** Runs the built backend's actual SQL/Java migrations without any Spring device worker. */
public final class SchemaMigrationRunner {
    public static void main(String[] args) throws Exception {
        var properties=new Properties();
        try(var input=Files.newInputStream(Path.of(args[0]))) {properties.load(input);}
        Flyway flyway=Flyway.configure()
                .dataSource(properties.getProperty("url"),properties.getProperty("user"),properties.getProperty("password"))
                .locations("classpath:db/migration")
                .target(properties.getProperty("target","20")).load();
        var result=flyway.migrate();
        flyway.validate();
        System.out.println("MIGRATION_COUNT="+result.migrationsExecuted);
        System.out.println("MIGRATION_VERSION="+flyway.info().current().getVersion());
    }
}
