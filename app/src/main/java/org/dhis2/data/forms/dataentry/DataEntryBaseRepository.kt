package org.dhis2.data.forms.dataentry

import org.dhis2.data.forms.dataentry.fields.FieldViewModelFactory
import org.dhis2.form.data.DataEntryRepository
import org.dhis2.form.model.FieldUiModel
import org.dhis2.form.model.SectionUiModelImpl
import org.hisp.dhis.android.core.D2
import org.hisp.dhis.android.core.program.ProgramStageSectionRenderingType

abstract class DataEntryBaseRepository(
    private val d2: D2,
    private val fieldFactory: FieldViewModelFactory
) : DataEntryRepository {
    override fun updateSection(
        sectionToUpdate: FieldUiModel,
        isSectionOpen: Boolean,
        totalFields: Int,
        fieldsWithValue: Int,
        errorCount: Int,
        warningCount: Int
    ): FieldUiModel {
        return (sectionToUpdate as SectionUiModelImpl).copy(
            isOpen = isSectionOpen,
            totalFields = totalFields,
            completedFields = fieldsWithValue,
            errors = errorCount,
            warnings = warningCount
        )
    }

    override fun updateField(
        fieldUiModel: FieldUiModel,
        warningMessage: String?,
        optionsToHide: MutableList<String>,
        optionGroupsToHide: MutableList<String>,
        optionGroupsToShow: MutableList<String>
    ): FieldUiModel {
        val optionsInGroupsToHide = optionsFromGroups(optionGroupsToHide)
        val optionsInGroupsToShow = optionsFromGroups(optionGroupsToShow)

        return when {
            fieldUiModel.optionSet != null -> {
                fieldUiModel.apply {
                    this.optionsToHide = listOf(optionsToHide, optionsInGroupsToHide).flatten()
                    this.optionsToShow = optionsInGroupsToShow
                }
            }
            else -> {
                fieldUiModel
            }
        }.apply {
            warningMessage?.let { setWarning(warningMessage) }
        }
    }

    private fun optionsFromGroups(optionGroupUids: List<String>): List<String> {
        if (optionGroupUids.isEmpty()) return emptyList()
        val optionsFromGroups = arrayListOf<String>()
        val optionGroups = d2.optionModule().optionGroups()
            .withOptions()
            .byUid().`in`(optionGroupUids)
            .blockingGet()
        for (optionGroup in optionGroups) {
            for (option in optionGroup.options()!!) {
                if (!optionsFromGroups.contains(option.uid())) {
                    optionsFromGroups.add(option.uid())
                }
            }
        }
        return optionsFromGroups
    }

    fun transformSection(
        sectionUid: String,
        sectionName: String?,
        sectionDescription: String? = null,
        isOpen: Boolean = false,
        totalFields: Int = 0,
        completedFields: Int = 0
    ): FieldUiModel {
        return fieldFactory.createSection(
            sectionUid,
            sectionName,
            sectionDescription,
            isOpen,
            totalFields,
            completedFields,
            ProgramStageSectionRenderingType.LISTING.name
        )
    }
}
