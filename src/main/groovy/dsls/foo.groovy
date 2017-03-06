package dsls

import ch.mibex.bamboo.plandsl.dsl.jobs.Requirement

def planConfig = [
        projectKey: 'TEMPL',
        projectName: '_Templates',
        planKey: 'DSLTST',
        planName: 'DSL Test',
]

def mainRepo = [
        projectKey: 'sb',
        repoSlug: 'foobar'
]

def mainPlanVariables = [
        artifactoryPath: 'test/foo',      // Most likely will be team/bin or team/lib
        jiraProjectKey: 'DSTRDP',         // Used in release notes generation
        projectName: 'foobar',            // Most of the times matches the binary name
        autoVersion: 'release_branches',  // Possible values: release_branches, master_minor, master_patch
        releasesRoot: 'DevServices',      // Subfolder of L:\Releases where to place the release. Case sensitive
]

project(key: "${planConfig.projectKey}", name: "${planConfig.projectName}") {
    plan(key: "${planConfig.planKey}", name: "${planConfig.planName}") {

        scm {
            stash {
                projectKey "${mainRepo.projectKey}"
                repoSlug "${mainRepo.repoSlug}"
            }
        }

        triggers {
            scheduled() {
                description 'Weekly build'
                cronExpression '0 0 0 ? * 7'
            }
        }

        notifications {
            committers(NotificationEvent.FAILED_BUILDS_AND_FIRST_SUCCESSFUL, {})
            responsibleUsers(NotificationEvent.FAILED_BUILDS_AND_FIRST_SUCCESSFUL, {})
        }

        variables {
            variable('artifactory_ci_repo', 'optiver-ci')
            variable('artifactory_path', "${mainPlanVariables.artifactsyPath}")
            variable('artifactory_releases_repo', 'optiver-releases')
            // variable('binary_names', 'foobar foobar_monitor')
            // variable('coverage_exclude', '/x_* /opt/centos* /opt/optiver* /usr/* /build/* /tests/*')
            variable('jira.projectKey', "${mainPlanVariables.jiraProjectKey}")
            variable('jira.username', '')
            variable('jira.version', '')
            variable('project_name', "${mainPlanVariables.projectName}")
            variable('pybuild_autoversion', "${mainPlanVariables.autoVersion}")
            variable('pybuild_virtualenv', 'pybuild')
            variable('releases_root', "/mnt/releases/${mainPlanVariables.releasesRoot}")
        }

        stage(name: 'Prepare') {
            job(key: 'PRE', name: 'Prepare') {
                requirements {
                    requirement(capabilityKey: 'os.linux', matchType: new Requirement.Exists()) {}
                }

                tasks {
                    script() {
                        description "Prepare build"
                        body CxxScripts.prepare_build
                    }

                    injectVariables()

                    script() {
                        description "Run pre-build checks"
                        body CxxScripts.checks_prebuild
                    }

                    parseTests('pre-build*.xml')
                }

                artifacts {
                    definition("cppcheck_report") {
                        location "cppcheck-html"
                        copyPattern "**"
                    }

                    definition("checks_prebuild") {
                        copyPattern "pre-build*.xml"
                    }
                }
            }
        }

        stage(name: 'Build') {
            job(key: 'BLD', name: 'Build Debug') {
                requirements {
                    requirement(capabilityKey: 'os.linux.centos.7', matchType: new Requirement.Exists()) {}
                    requirement(capabilityKey: 'cpp.devtoolset', matchType: new Requirement.Exists()) {}
                    requirement(capabilityKey: 'type', matchType: new Requirement.Equals('physical')) {}
                }

                tasks {
                    script("Build debug") {
                        body CxxScripts.build_debug
                    }

                    parseTests('build/unit_tests_results/*.xml')
                }

                artifacts {
                    definition("debug_build") {
                        location "artifacts_out"
                        copyPattern "**"
                        shared false
                    }
                }
            }

            job(key: 'RBCENTOS', name: 'Build Release') {
                requirements {
                    requirement(capabilityKey: 'os.linux.centos.7', matchType: new Requirement.Exists()) {}
                    requirement(capabilityKey: 'cpp.devtoolset', matchType: new Requirement.Exists()) {}
                    requirement(capabilityKey: 'type', matchType: new Requirement.Equals('physical')) {}
                }

                tasks {
                    script("Build release") {
                        body CxxScripts.build_release
                    }

                    parseTests('build/unit_tests_results/*.xml')
                }

                artifacts {
                    definition("release_artifactory_build_info") {
                        copyPattern "build_info.json"
                    }

                    definition("release_build") {
                        location "artifacts_out"
                        copyPattern "**/*"
                    }

                    definition("release_unit_tests") {
                        location "build/unit_tests_results"
                        copyPattern "*.xml"
                    }
                }
            }

            job(key: 'COV', name: 'Coverage') {
                requirements {
                    requirement(capabilityKey: 'os.linux.centos.7', matchType: new Requirement.Exists()) {}
                    requirement(capabilityKey: 'cpp.devtoolset', matchType: new Requirement.Exists()) {}
                    requirement(capabilityKey: 'type', matchType: new Requirement.Equals('physical')) {}
                }

                tasks {
                    script("Generate coverage report") {
                        body CxxScripts.generate_coverage
                    }
                }

                artifacts {
                    definition("coverage_report") {
                        location "coverage"
                        copyPattern "**/*"
                    }
                }
            }

            job(key: 'DOX', name: 'Doxygen') {
                requirements {
                    requirement(capabilityKey: 'os.linux', matchType: new Requirement.Exists()) {}
                }

                tasks {
                    script("Generate doxygen") {
                        body CxxScripts.generate_doxygen
                    }
                }

                artifacts {
                    definition("doxygen_report") {
                        location "source/html"
                        copyPattern "**/*"
                    }
                }
            }
        }

        stage(name: "Publish") {
            job(key: "PUB", name: "Publish") {
                requirements {
                    requirement(capabilityKey: 'os.linux', matchType: new Requirement.Exists()) {}
                }

                tasks {
                    script("Post-build checks") {
                        body CxxScripts.checks_postbuild
                    }

                    script("Publish") {
                        body CxxScripts.publish
                    }

                    parseTests('post-build*.xml')
                }

                artifacts {
                    definition("checks_postbuild") {
                        copyPattern "post-build*.xml"
                    }

                    dependency("release_artifactory_build_info") {
                    }

                    dependency("release_build") {
                        destinationDirectory "artifacts_in"
                    }
                }
            }
        }

        stage(name: "Promote") {
            manual true
            job(key: "REL", name: "Promote") {
                requirements {
                    requirement(capabilityKey: 'os.linux.centos.7', matchType: new Requirement.Exists()) {}
                }

                tasks {
                    script("pre-promotion checks") {
                        body CxxScripts.checks_prepromotion
                    }

                    script("promotion tasks") {
                        body CxxScripts.promote
                    }

                    parseTests('pre-promote*.xml')
                }

                artifacts {
                    definition("checks_prepromote") {
                        copyPattern "pre-promote*.xml"
                        shared false
                    }

                    dependency("checks_prebuild") {
                        destinationDirectory "reports"
                    }

                    dependency("release_build") {
                        destinationDirectory "artifacts_in"
                    }

                    dependency("release_unit_tests") {
                        destinationDirectory "reports"
                    }

                    dependency("doxygen_report") {
                        destinationDirectory "reports/doxygen"
                    }

                    dependency("coverage_report") {
                        destinationDirectory "reports/coverage"
                    }

                    dependency("checks_postbuild") {
                        destinationDirectory "reports"
                    }
                }
            }
        }
    }
}