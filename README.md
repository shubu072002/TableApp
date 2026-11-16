# Table Export App

Connect to a **PostgreSQL, Oracle or MySQL** database from the browser by entering host,
port, database, username and password. The app lists all tables. Select a table to see
its columns and row count, then download **all rows and all columns** as **CSV** or
**Excel (.xlsx)**. Includes a Day / Night theme.

| Part     | Tech                                                       | Folder      |
|----------|------------------------------------------------------------|-------------|
| Backend  | Spring Boot 3.5.11, Java 21+ (JDK 25 OK), JDBC + HikariCP  | `backend/`  |
| Frontend | React 18 + Vite 5 (Node 22)                                | `frontend/` |
| Drivers  | PostgreSQL, Oracle (ojdbc11), MySQL (Connector/J), bundled | —           |

## Start

```powershell
cd C:\FCI\TableApp
.\start-app.ps1
```

Or start the two parts by hand:

```powershell
# terminal 1 – backend (http://localhost:8080)
cd C:\FCI\TableApp\backend
mvn spring-boot:run

# terminal 2 – UI (http://localhost:5173)
cd C:\FCI\TableApp\frontend
npm install      # first time only
npm run dev
```

You do not need to put any database settings in configuration files. Everything is entered
on the connect screen.

> Do not use VS Code's "Run Code" (▷) on `TableExportApplication.java`. Spring Boot must be
> started with `mvn spring-boot:run`.

## Connect screen

| Field | PostgreSQL | Oracle | MySQL |
|---|---|---|---|
| Port | 5432 | 1521 | 3306 |
| Database | database name (`taskdb`) | **service name** (`ORCL`, `XEPDB1`) or **SID** | database name |
| Schema (optional) | default `public` | default = your username | not used |

Notes:

- The last-used details for each database type are remembered in the browser. The password is never saved.
- Sessions close automatically after 30 idle minutes (`app.session.idle-minutes`).
- Use **Disconnect** in the header to close a session.

## REST API

| Method | URL | Purpose |
|---|---|---|
| POST | `/api/connections` | body `{dbType, host, port, database, oracleMode, username, password, schema}` → `{id, info}` |
| GET | `/api/connections/{id}` | connection info |
| DELETE | `/api/connections/{id}` | disconnect |
| GET | `/api/connections/{id}/tables` | list tables |
| GET | `/api/connections/{id}/tables/details?schema=&table=` | columns + row count |
| GET | `/api/connections/{id}/tables/download?schema=&table=&format=csv\|xlsx` | file download |

## Security

- The browser never sends a JDBC URL. The backend builds it from validated fields (host,
  port, database name), so driver options cannot be injected.
- Passwords live only inside the in-memory connection pool. They are never logged or written
  to disk.
- PostgreSQL and MySQL sessions are opened **read-only**.
- Table names are checked against the real table list and quoted before any query runs.
- The connection id works like a login token for that session. Run the app on a trusted
  network only, because it lets the user connect to any host the server can reach.

## Single-jar deployment (optional)

```powershell
cd C:\FCI\TableApp\frontend;  npm run build:backend
cd C:\FCI\TableApp\backend;   mvn package -DskipTests
java -jar target\table-export-1.0.0.jar      # open http://localhost:8080
```
