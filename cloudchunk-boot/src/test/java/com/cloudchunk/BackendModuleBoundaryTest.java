package com.cloudchunk;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** Executed from boot so every runtime module is present on the test classpath. */
class BackendModuleBoundaryTest {

    private final JavaClasses classes = new ClassFileImporter().importPackages("com.cloudchunk");

    @Test
    void stableFoundationModulesDoNotDependOnApplicationOrAdapters() {
        noClasses().that().resideInAPackage("com.cloudchunk.contract..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.cloudchunk.core..", "com.cloudchunk.infra..", "com.cloudchunk.storage..",
                        "com.cloudchunk.api..", "com.cloudchunk.mq..", "com.cloudchunk.transcode..")
                .check(classes);

        noClasses().that().resideInAPackage("com.cloudchunk.infra..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.cloudchunk.core..", "com.cloudchunk.storage..", "com.cloudchunk.api..",
                        "com.cloudchunk.mq..", "com.cloudchunk.transcode..")
                .check(classes);

        noClasses().that().resideInAPackage("com.cloudchunk.storage..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.cloudchunk.core..", "com.cloudchunk.infra..", "com.cloudchunk.api..",
                        "com.cloudchunk.mq..", "com.cloudchunk.transcode..")
                .check(classes);
    }

    @Test
    void coreDoesNotDependOnDeliveryOrWorkerAdapters() {
        noClasses().that().resideInAPackage("com.cloudchunk.core..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.cloudchunk.api..", "com.cloudchunk.mq..", "com.cloudchunk.transcode..")
                .check(classes);
    }

    @Test
    void deliveryAndWorkerAdaptersRemainIndependent() {
        noClasses().that().resideInAPackage("com.cloudchunk.api..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.cloudchunk.mq..", "com.cloudchunk.transcode..")
                .check(classes);

        noClasses().that().resideInAPackage("com.cloudchunk.mq..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.cloudchunk.api..", "com.cloudchunk.transcode..")
                .check(classes);

        noClasses().that().resideInAPackage("com.cloudchunk.transcode..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.cloudchunk.api..", "com.cloudchunk.mq..")
                .check(classes);
    }
}
