package rw.bloomco.document;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * Data-access layer for the document (NoSQL) side of the system:
 *   contactmessages  - "Contact Us" messages with an embedded reply thread
 *   auditlogs        - append-only audit trail of business / security events
 *   notifications    - outbox log of every email / SMS dispatched
 * Uses MongoDB when connected, otherwise a bounded in-memory store.
 */
@Component
public class DocumentStore {

    private static final Logger log = LoggerFactory.getLogger(DocumentStore.class);
    private static final int MAX = 500;
    public static final String CONTACT = "contactmessages";
    public static final String AUDIT = "auditlogs";
    public static final String NOTIFICATIONS = "notifications";

    private final MongoTemplate mongo;
    private volatile boolean ready;
    private final Map<String, List<Map<String, Object>>> memory = Map.of(
            CONTACT, new CopyOnWriteArrayList<>(), AUDIT, new CopyOnWriteArrayList<>(), NOTIFICATIONS, new CopyOnWriteArrayList<>());

    public DocumentStore(ObjectProvider<MongoTemplate> mongo) {
        this.mongo = mongo.getIfAvailable();
    }

    @PostConstruct
    void connect() {
        if (mongo == null) {
            log.warn("MONGO_URI not set - document store running in memory-only fallback mode");
            return;
        }
        try {
            mongo.executeCommand(new Document("ping", 1));
            mongo.indexOps(CONTACT).ensureIndex(new Index().on("status", Sort.Direction.ASC));
            mongo.indexOps(AUDIT).ensureIndex(new Index().on("createdAt", Sort.Direction.DESC));
            mongo.indexOps(AUDIT).ensureIndex(new Index().on("event", Sort.Direction.ASC));
            mongo.indexOps(NOTIFICATIONS).ensureIndex(new Index().on("userId", Sort.Direction.ASC));
            ready = true;
            log.info("MongoDB connected ({})", mongo.getDb().getName());
        } catch (Exception e) {
            log.warn("MongoDB unavailable ({}) - using in-memory fallback", e.getMessage());
        }
    }

    public boolean isMongoReady() {
        return ready;
    }

    // ---------------------------------------------------------------- contact messages

    public Map<String, Object> createContact(Map<String, Object> data) {
        Map<String, Object> doc = new LinkedHashMap<>(data);
        doc.put("status", "new");
        doc.put("replies", new ArrayList<>());
        return insert(CONTACT, doc, true);
    }

    public List<Map<String, Object>> listContacts(String status) {
        if (ready) {
            Query q = status == null ? new Query() : Query.query(Criteria.where("status").is(status));
            q.with(Sort.by(Sort.Direction.DESC, "createdAt")).limit(200);
            return mongo.find(q, Document.class, CONTACT).stream().map(DocumentStore::toMap).toList();
        }
        return memory.get(CONTACT).stream().filter(m -> status == null || status.equals(m.get("status"))).toList();
    }

    /** Applies a status change and (optionally) appends a reply. Returns null when not found. */
    public Map<String, Object> updateContact(String id, String status, Map<String, Object> reply) {
        if (ready) {
            if (!ObjectId.isValid(id)) return null;
            Update u = new Update().set("status", status).set("updatedAt", new Date());
            if (reply != null) u.push("replies", new Document(withAt(reply)));
            Document d = mongo.findAndModify(Query.query(Criteria.where("_id").is(new ObjectId(id))), u,
                    FindAndModifyOptions.options().returnNew(true), Document.class, CONTACT);
            return d == null ? null : toMap(d);
        }
        for (Map<String, Object> m : memory.get(CONTACT)) {
            if (id.equals(m.get("_id"))) {
                m.put("status", status);
                m.put("updatedAt", Instant.now());
                if (reply != null) {
                    @SuppressWarnings("unchecked")
                    List<Object> replies = (List<Object>) m.get("replies");
                    replies.add(withAt(reply));
                }
                return m;
            }
        }
        return null;
    }

    public long countNewContacts() {
        if (ready) return mongo.count(Query.query(Criteria.where("status").is("new")), CONTACT);
        return memory.get(CONTACT).stream().filter(m -> "new".equals(m.get("status"))).count();
    }

    // ---------------------------------------------------------------- audit + notifications

    public void recordAudit(Map<String, Object> entry) {
        insert(AUDIT, new LinkedHashMap<>(entry), false);
    }

    public List<Map<String, Object>> recentAudit(int limit) {
        return recent(AUDIT, limit);
    }

    public void recordNotification(Map<String, Object> entry) {
        insert(NOTIFICATIONS, new LinkedHashMap<>(entry), false);
    }

    public List<Map<String, Object>> recentNotifications(int limit) {
        return recent(NOTIFICATIONS, limit);
    }

    // ---------------------------------------------------------------- internals

    private Map<String, Object> insert(String collection, Map<String, Object> doc, boolean timestamps) {
        Instant now = Instant.now();
        doc.values().removeIf(v -> v == null);
        if (ready) {
            Document d = new Document(doc);
            d.put("createdAt", Date.from(now));
            if (timestamps) d.put("updatedAt", Date.from(now));
            mongo.insert(d, collection);
            return toMap(d);
        }
        doc.put("_id", UUID.randomUUID().toString());
        doc.put("createdAt", now);
        if (timestamps) doc.put("updatedAt", now);
        List<Map<String, Object>> list = memory.get(collection);
        list.add(0, doc);
        while (list.size() > MAX) list.remove(list.size() - 1);
        return doc;
    }

    private List<Map<String, Object>> recent(String collection, int limit) {
        if (ready) {
            Query q = new Query().with(Sort.by(Sort.Direction.DESC, "createdAt")).limit(limit);
            return mongo.find(q, Document.class, collection).stream().map(DocumentStore::toMap).toList();
        }
        return memory.get(collection).stream().limit(limit).toList();
    }

    private static Map<String, Object> withAt(Map<String, Object> reply) {
        Map<String, Object> r = new LinkedHashMap<>(reply);
        r.put("at", new Date());
        return r;
    }

    /** BSON -> JSON-friendly map (ObjectId -> string, Date -> Instant). */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(Document d) {
        Map<String, Object> out = new LinkedHashMap<>();
        d.forEach((k, v) -> out.put(k, convert(v)));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Object convert(Object v) {
        if (v instanceof ObjectId oid) return oid.toHexString();
        if (v instanceof Date date) return date.toInstant();
        if (v instanceof Document doc) return toMap(doc);
        if (v instanceof List<?> list) return list.stream().map(DocumentStore::convert).toList();
        return v;
    }
}
