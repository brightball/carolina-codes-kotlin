.PHONY: test sast deps gitleaks style check hooks

test:
	./gradlew --no-daemon test

sast:
	./gradlew --no-daemon detekt

deps:
	./gradlew --no-daemon osvScan

gitleaks:
	gitleaks detect --source . --verbose

style:
	./gradlew --no-daemon ktlintCheck

check:
	pre-commit run --all-files

hooks:
	pre-commit install
	git config core.hooksPath .githooks
