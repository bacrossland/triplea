# Local Database Development

- Local database is run on docker containers
- You need a local database to run the integration tests
- You need a local database to run the application servers

We run multiple schemas on one database. All schema info
and database config is in the 'database' subproject.

##Quick-Start

- Install docker on your system
- Install libpq or postgres on your system
- Run `database/start_docker_db`

## Working with Database

Convenience scripts are checked in to make typical operations
easy and available for reference by viewing the script contents.

To connect to the locally running database with a psql CLI:
```
./database/connect_to_docker_db
```

To drop and recreate the database, run:
```
./database/reset_docker_db
```

