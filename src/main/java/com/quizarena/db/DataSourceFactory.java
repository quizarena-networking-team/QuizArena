package com.quizarena.db;

import com.mysql.cj.jdbc.MysqlDataSource;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Objects;

/** Creates the shared MySQL data source without opening a connection eagerly. */
public final class DataSourceFactory {
    private DataSourceFactory() {
    }

    public static DataSource create(DatabaseConfig config) {
        Objects.requireNonNull(config, "config");
        var source = new MysqlDataSource();
        source.setURL(config.url());
        source.setUser(config.user());
        source.setPassword(config.password());
        try {
            source.setConnectTimeout(Math.toIntExact(config.connectTimeout().toMillis()));
            source.setSocketTimeout(Math.toIntExact(config.socketTimeout().toMillis()));
        } catch (SQLException failure) {
            throw new IllegalArgumentException("Invalid MySQL data source configuration", failure);
        }
        return source;
    }
}

