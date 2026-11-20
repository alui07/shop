package dev.shop.api.inventory;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class StockRepository {

    private final JdbcClient jdbc;

    StockRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insertEmpty(long productId) {
        jdbc.sql("INSERT INTO stock (product_id, on_hand) VALUES (:id, 0)")
                .param("id", productId)
                .update();
    }

    Optional<StockLevel> find(long productId) {
        return jdbc.sql("SELECT on_hand, reserved, version FROM stock WHERE product_id = :id")
                .param("id", productId)
                .query(StockRepository::mapLevel)
                .optional();
    }

    Optional<StockLevel> lock(long productId) {
        return jdbc.sql("SELECT on_hand, reserved, version FROM stock WHERE product_id = :id FOR UPDATE")
                .param("id", productId)
                .query(StockRepository::mapLevel)
                .optional();
    }

    Map<Long, StockLevel> findAll(Collection<Long> productIds) {
        Map<Long, StockLevel> levels = new HashMap<>();
        jdbc.sql("SELECT product_id, on_hand, reserved, version FROM stock WHERE product_id IN (:ids)")
                .param("ids", productIds)
                .query(rs -> {
                    levels.put(rs.getLong("product_id"), mapLevel(rs, 0));
                });
        return levels;
    }

    void setOnHand(long productId, int onHand) {
        jdbc.sql("UPDATE stock SET on_hand = :onHand, version = version + 1 WHERE product_id = :id")
                .param("id", productId)
                .param("onHand", onHand)
                .update();
    }

    private static StockLevel mapLevel(ResultSet rs, int rowNum) throws SQLException {
        return new StockLevel(rs.getInt("on_hand"), rs.getInt("reserved"), rs.getLong("version"));
    }
}
