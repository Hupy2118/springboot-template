# Backend template development

`base` and `extensions` are the only source owners. `workspace` is an assembled
Spring Boot project and must be synchronized before its changes are considered
template source changes.

Use `./backend-template <command> [profile]`. The maintenance tooling needs only
a JDK and this repository's Maven Wrapper; it does not require Node.js.

Profiles are `base`, `login`, `authorization`, and `full`. `authorization`
automatically includes `login`; `full` is integration-only and cannot sync new
files because it has no edit target.

For the complete maintainer workflow, see [docs/development-guide.md](docs/development-guide.md).
