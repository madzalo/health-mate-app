package org.dhis2.form.data

import android.os.Build
import android.text.TextUtils.isEmpty
import io.reactivex.Single
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.dhis2.commons.rules.toRuleEngineInstant
import org.dhis2.commons.rules.toRuleEngineLocalDate
import org.dhis2.form.bindings.toRuleAttributeValue
import org.dhis2.form.bindings.toRuleDataValue
import org.dhis2.form.bindings.toRuleList
import org.dhis2.form.bindings.toRuleVariable
import org.dhis2.form.bindings.toRuleVariableList
import org.hisp.dhis.android.core.D2
import org.hisp.dhis.android.core.arch.helpers.UidsHelper
import org.hisp.dhis.android.core.arch.repositories.scope.RepositoryScope
import org.hisp.dhis.android.core.enrollment.Enrollment
import org.hisp.dhis.android.core.event.Event
import org.hisp.dhis.android.core.event.EventStatus
import org.hisp.dhis.android.core.program.ProgramRule
import org.hisp.dhis.rules.models.Rule
import org.hisp.dhis.rules.models.RuleAttributeValue
import org.hisp.dhis.rules.models.RuleEnrollment
import org.hisp.dhis.rules.models.RuleEnrollmentStatus
import org.hisp.dhis.rules.models.RuleEvent
import org.hisp.dhis.rules.models.RuleEventStatus
import org.hisp.dhis.rules.models.RuleVariable
import java.util.Calendar
import java.util.Date
import java.util.Objects

class RulesRepository(private val d2: D2) {

    // ORG UNIT GROUPS
    // USER ROLES
    fun supplementaryData(orgUnitUid: String): Single<Map<String, List<String>>> {
        return Single.fromCallable {
            val supData = HashMap<String, List<String>>()

            d2.organisationUnitModule().organisationUnits()
                .withOrganisationUnitGroups().uid(orgUnitUid).blockingGet()
                .let { orgUnit ->
                    orgUnit?.organisationUnitGroups()?.map {
                        if (it.code() != null) {
                            supData[it.code()!!] = arrayListOf(orgUnit.uid())
                        }
                        supData[it.uid()!!] = arrayListOf(orgUnit.uid())
                    }
                }

            val userRoleUids =
                UidsHelper.getUidsList(d2.userModule().userRoles().blockingGet())
            supData["USER"] = userRoleUids
            supData["android_version"] = listOf(Build.VERSION.SDK_INT.toString())

            supData
        }
    }

    fun rulesNew(programUid: String, eventUid: String? = null): Single<List<Rule>> {
        return queryRules(programUid)
            .map { it.toRuleList() }
            .map {
                if (eventUid != null) {
                    val stage = d2.eventModule().events()
                        .uid(eventUid)
                        .blockingGet()
                        ?.programStage()
                    it.filter { rule ->
                        rule.programStage == null || rule.programStage == stage
                    }
                } else {
                    it
                }
            }
    }

    fun ruleVariables(programUid: String): Single<List<RuleVariable>> {
        return d2.programModule().programRuleVariables().byProgramUid().eq(programUid).get()
            .map {
                it.toRuleVariableList(
                    d2.trackedEntityModule().trackedEntityAttributes(),
                    d2.dataElementModule().dataElements(),
                    d2.optionModule().options(),
                )
            }
    }

    fun ruleVariablesProgramStages(programUid: String): Single<List<RuleVariable>> {
        return d2.programModule().programRuleVariables().byProgramUid().eq(programUid).get()
            .toFlowable().flatMapIterable { list -> list }
            .map {
                it.toRuleVariable(
                    d2.trackedEntityModule().trackedEntityAttributes(),
                    d2.dataElementModule().dataElements(),
                    d2.optionModule().options(),
                )
            }
            .toList()
    }

    fun queryConstants(): Single<Map<String, String>> {
        return d2.constantModule().constants().get()
            .map { constants ->
                val constantsMap = HashMap<String, String>()
                for (constant in constants) {
                    constantsMap[constant.uid()] =
                        Objects.requireNonNull<Double>(constant.value()).toString()
                }
                constantsMap
            }
    }

    private fun queryRules(programUid: String): Single<List<ProgramRule>> {
        return d2.programModule().programRules()
            .byProgramUid().eq(programUid)
            .withProgramRuleActions()
            .get()
    }

    fun otherEvents(eventUidToEvaluate: String): Single<List<RuleEvent>> {
        return d2.eventModule().events().uid(eventUidToEvaluate).get()
            .flatMap { eventToEvaluate ->
                getOtherEventList(eventToEvaluate).toFlowable()
                    .flatMapIterable { eventList -> eventList }
                    .map { event ->
                        RuleEvent(
                            event = event.uid(),
                            programStage = event.programStage()!!,
                            programStageName = d2.programModule().programStages()
                                .uid(event.programStage())
                                .blockingGet()!!.name()!!,
                            status = if (event.status() == EventStatus.VISITED) {
                                RuleEventStatus.ACTIVE
                            } else {
                                RuleEventStatus.valueOf(event.status()!!.name)
                            },
                            eventDate = Instant.fromEpochMilliseconds(event.eventDate()!!.time),
                            dueDate = event.dueDate()?.let {
                                Instant.fromEpochMilliseconds(it.time)
                                    .toLocalDateTime(TimeZone.currentSystemDefault()).date
                            },
                            completedDate = event.completedDate()?.let {
                                Instant.fromEpochMilliseconds(it.time)
                                    .toLocalDateTime(TimeZone.currentSystemDefault()).date
                            },
                            organisationUnit = event.organisationUnit()!!,
                            organisationUnitCode = d2.organisationUnitModule().organisationUnits()
                                .uid(
                                    event.organisationUnit(),
                                ).blockingGet()?.code(),
                            dataValues = event.trackedEntityDataValues()?.toRuleDataValue(
                                event,
                                d2.dataElementModule().dataElements(),
                                d2.programModule().programRuleVariables(),
                                d2.optionModule().options(),
                            ) ?: emptyList(),
                        )
                    }
                    .toList()
            }
    }

    private fun getOtherEventList(eventToEvaluate: Event): Single<List<Event>> {
        return if (!isEmpty(eventToEvaluate.enrollment())) {
            d2.eventModule().events().byProgramUid().eq(eventToEvaluate.program())
                .byEnrollmentUid().eq(eventToEvaluate.enrollment())
                .byUid().notIn(eventToEvaluate.uid())
                .byStatus().notIn(EventStatus.SCHEDULE, EventStatus.SKIPPED, EventStatus.OVERDUE)
                .byEventDate().beforeOrEqual(Date())
                .withTrackedEntityDataValues()
                .orderByEventDate(RepositoryScope.OrderByDirection.DESC)
                .get()
        } else {
            d2.eventModule().events()
                .byProgramUid().eq(eventToEvaluate.program())
                .byProgramStageUid().eq(eventToEvaluate.programStage())
                .byOrganisationUnitUid().eq(eventToEvaluate.organisationUnit())
                .byStatus().notIn(EventStatus.SCHEDULE, EventStatus.SKIPPED, EventStatus.OVERDUE)
                .byEventDate().beforeOrEqual(Date())
                .withTrackedEntityDataValues()
                .orderByEventDate(RepositoryScope.OrderByDirection.DESC)
                .get().map { list ->
                    val currentEventIndex = list.indexOfFirst { it.uid() == eventToEvaluate.uid() }

                    var newEvents = if (currentEventIndex != -1) {
                        list.subList(0, currentEventIndex)
                    } else {
                        emptyList<Event>()
                    }
                    var previousEvents = if (currentEventIndex != -1) {
                        list.subList(currentEventIndex + 1, list.size)
                    } else {
                        list
                    }

                    if (newEvents.size > 10) {
                        newEvents = newEvents.subList(0, 10)
                    }
                    if (previousEvents.size > 10) {
                        previousEvents = previousEvents.subList(0, 10)
                    }

                    val finalList = ArrayList<Event>()
                    finalList.addAll(newEvents)
                    finalList.addAll(previousEvents)

                    finalList
                }
        }
    }

    fun enrollmentEvents(enrollmentUid: String): Single<List<RuleEvent>> {
        return d2.eventModule().events().byEnrollmentUid().eq(enrollmentUid)
            .byStatus().notIn(EventStatus.SCHEDULE, EventStatus.SKIPPED, EventStatus.OVERDUE)
            .byEventDate().beforeOrEqual(Date())
            .withTrackedEntityDataValues()
            .get()
            .toFlowable().flatMapIterable { events -> events }
            .map { event ->
                RuleEvent(
                    event = event.uid(),
                    programStage = event.programStage()!!,
                    programStageName =
                    d2.programModule().programStages().uid(event.programStage())
                        .blockingGet()!!.name()!!,
                    status =
                    if (event.status() == EventStatus.VISITED) {
                        RuleEventStatus.ACTIVE
                    } else {
                        RuleEventStatus.valueOf(event.status()!!.name)
                    },
                    eventDate = event.eventDate()!!.toRuleEngineInstant(),
                    dueDate = event.dueDate()?.toRuleEngineLocalDate(),
                    completedDate = event.completedDate()?.toRuleEngineLocalDate(),
                    organisationUnit = event.organisationUnit()!!,
                    organisationUnitCode = d2.organisationUnitModule()
                        .organisationUnits().uid(event.organisationUnit())
                        .blockingGet()?.code(),
                    dataValues =
                    event.trackedEntityDataValues()?.toRuleDataValue(
                        event,
                        d2.dataElementModule().dataElements(),
                        d2.programModule().programRuleVariables(),
                        d2.optionModule().options(),
                    ) ?: emptyList(),
                )
            }.toList()
    }

    fun enrollment(eventUid: String): Single<RuleEnrollment> {
        return d2.eventModule().events().uid(eventUid).get()
            .flatMap { event ->
                val ouCode = d2.organisationUnitModule().organisationUnits()
                    .uid(event.organisationUnit())
                    .blockingGet()?.code() ?: ""
                val programName =
                    d2.programModule().programs().uid(event.program()).blockingGet()!!.name()
                if (event.enrollment() == null) {
                    Single.just(
                        RuleEnrollment(
                            "",
                            programName!!,
                            Calendar.getInstance().time.toRuleEngineLocalDate(),
                            Calendar.getInstance().time.toRuleEngineLocalDate(),
                            RuleEnrollmentStatus.CANCELLED,
                            event.organisationUnit()!!,
                            ouCode,
                            ArrayList(),
                        ),
                    )
                } else {
                    d2.enrollmentModule().enrollments()
                        .uid(event.enrollment()).get()
                        .map { enrollment ->
                            RuleEnrollment(
                                enrollment.uid(),
                                programName!!,
                                (enrollment.incidentDate() ?: Date()).toRuleEngineLocalDate(),
                                enrollment.enrollmentDate()!!.toRuleEngineLocalDate(),
                                RuleEnrollmentStatus.valueOf(enrollment.status()!!.name),
                                event.organisationUnit()!!,
                                ouCode,
                                getAttributesValues(enrollment),
                            )
                        }
                }
            }
    }

    private fun getAttributesValues(enrollment: Enrollment): List<RuleAttributeValue> {
        val attributeValues = d2.trackedEntityModule().trackedEntityAttributeValues()
            .byTrackedEntityInstance().eq(enrollment.trackedEntityInstance()).blockingGet()
        return attributeValues.toRuleAttributeValue(d2, enrollment.program()!!)
    }
}
