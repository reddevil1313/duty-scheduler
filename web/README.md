# Duty Scheduler — web client

React 19 + TypeScript, built with Vite. It talks to the Spring Boot API in the
sibling Gradle project and holds no scheduling logic of its own.

## Running it

    npm install
    npm run dev        # http://localhost:5173, proxying /api to :8080

Start the server first (`./gradlew bootRun`); it seeds two dev accounts,
`admin` / `admin` and `trooper_d` / `trooper_d`. The dev server proxies `/api` and
`/actuator` to it, which is what makes the session cookie first-party in
development — pointing the client straight at `:8080` would mean fighting CORS
and SameSite over a problem that does not exist in production behind one host.

## Scripts

| command | does |
| --- | --- |
| `npm run dev` | dev server with hot reload |
| `npm run typecheck` | `tsc -b --noEmit` |
| `npm run lint` | oxlint |
| `npm run build` | typecheck, then a production bundle in `dist/` |

## Layout

    src/api/        types.ts mirrors the Java DTOs; client.ts is the only file that calls fetch
    src/auth/       session context and provider — holds identity and the CSRF token
    src/board/      the duty board, and slots.ts for the 2000-start clock
    src/roster/     the roster tab, and the MC/OL/AL ticks held for generating
    src/leave/      the leave register, a table per kind
    src/records/    totals per Trooper and the days logged
    src/team/       the owner's teams, invite links and the Team tab
    src/help/       the Help tab, written per role
    src/dates.ts    dates as the API sends them, and as the board writes them
    src/theme.ts    light, dark or the system's, remembered in the browser
