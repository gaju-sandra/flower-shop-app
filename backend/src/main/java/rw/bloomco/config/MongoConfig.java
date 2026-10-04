package rw.bloomco.config;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * MongoDB (document store) - only wired when MONGO_URI is set.
 * Without it, DocumentStore keeps documents in memory so the shop still runs.
 */
@Configuration
@ConditionalOnExpression("!'${app.mongo.uri:}'.isEmpty()")
public class MongoConfig {

    @Bean(destroyMethod = "close")
    public MongoClient mongoClient(AppProperties props) {
        ConnectionString cs = new ConnectionString(props.mongo().uri());
        return MongoClients.create(MongoClientSettings.builder()
                .applyConnectionString(cs)
                .applyToClusterSettings(c -> c.serverSelectionTimeout(4, TimeUnit.SECONDS))
                .build());
    }

    @Bean
    public MongoTemplate mongoTemplate(MongoClient client, AppProperties props) {
        String db = new ConnectionString(props.mongo().uri()).getDatabase();
        return new MongoTemplate(client, db == null ? "flower_shop" : db);
    }
}
