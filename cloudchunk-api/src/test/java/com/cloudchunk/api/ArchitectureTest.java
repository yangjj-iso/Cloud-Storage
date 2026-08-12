package com.cloudchunk.api;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ArchitectureTest {

    private final com.tngtech.archunit.core.domain.JavaClasses classes =
            new ClassFileImporter().importPackages("com.cloudchunk");

    @Test
    void controllersDoNotBypassApplicationBoundary() {
        noClasses().that().resideInAPackage("com.cloudchunk.api.controller..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..mapper..", "com.cloudchunk.infra..", "com.cloudchunk.storage..", "com.cloudchunk.mq..")
                .check(classes);
    }

    @Test
    void coreDoesNotDependOnMessagingAdapter() {
        noClasses().that().resideInAPackage("com.cloudchunk.core..")
                .should().dependOnClassesThat().resideInAPackage("com.cloudchunk.mq..")
                .check(classes);
    }

    @Test
    void contractDoesNotDependOnApplicationOrAdapters() {
        noClasses().that().resideInAPackage("com.cloudchunk.contract..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.cloudchunk.core..", "com.cloudchunk.infra..", "com.cloudchunk.storage..",
                        "com.cloudchunk.mq..", "com.cloudchunk.api..")
                .check(classes);
    }
}
