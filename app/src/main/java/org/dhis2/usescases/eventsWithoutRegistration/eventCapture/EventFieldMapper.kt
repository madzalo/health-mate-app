package org.dhis2.usescases.eventsWithoutRegistration.eventCapture

import java.util.ArrayList
import java.util.HashMap
import org.dhis2.data.forms.FormSectionViewModel
import org.dhis2.data.forms.dataentry.fields.FieldViewModel
import org.dhis2.data.forms.dataentry.fields.display.DisplayViewModel
import org.dhis2.data.forms.dataentry.fields.image.ImageViewModel
import org.dhis2.data.forms.dataentry.fields.section.SectionViewModel
import org.dhis2.data.forms.dataentry.fields.unsupported.UnsupportedViewModel
import org.dhis2.utils.DhisTextUtils.Companion.isEmpty

const val DISPLAY_FIELD_KEY = "DISPLAY_FIELD_KEY"

class EventFieldMapper {

    var totalFields: Int = 0
    var unsupportedFields: Int = 0
    private lateinit var optionSets: MutableList<String?>
    private lateinit var fieldMap: MutableMap<String?, MutableList<FieldViewModel>>
    private lateinit var eventSectionModels: MutableList<EventSectionModel>
    private lateinit var finalFieldList: MutableList<FieldViewModel>
    private lateinit var finalFields: MutableMap<String, Boolean>

    fun map(
        fields: List<FieldViewModel>,
        sectionList: List<FormSectionViewModel>,
        sectionsToHide: List<String?>,
        currentSection: String
    ): Pair<List<EventSectionModel>, List<FieldViewModel>> {
        clearAll()
        setFieldMap(fields, sectionList, sectionsToHide)
        sectionList.forEach {
            handleSection(fields, sectionList, sectionsToHide, it, currentSection)
        }

        if (eventSectionModels.first().sectionName() == "NO_SECTION") {
            finalFieldList.add(SectionViewModel.createClosingSection())
        }
        if (fieldMap.containsKey(DISPLAY_FIELD_KEY) && fieldMap[DISPLAY_FIELD_KEY] != null) {
            finalFieldList.addAll(fieldMap[DISPLAY_FIELD_KEY] as Collection<FieldViewModel>)
        }

        return Pair(eventSectionModels, finalFieldList)
    }

    private fun clearAll() {
        totalFields = 0
        unsupportedFields = 0
        optionSets = mutableListOf()
        fieldMap = HashMap()
        eventSectionModels = mutableListOf()
        finalFieldList = mutableListOf()
        finalFields = HashMap()
    }

    private fun setFieldMap(
        fields: List<FieldViewModel>,
        sectionList: List<FormSectionViewModel>,
        sectionsToHide: List<String?>
    ) {
        fields.forEach { field ->
            val fieldSection = getFieldSection(field)
            if (fieldSection.isNotEmpty() || sectionList.size == 1) {
                updateFieldMap(fieldSection, field)
                if (field !is DisplayViewModel &&
                    !sectionsToHide.contains(field.programStageSection())
                ) {
                    if (fieldIsNotOptionSetOrImage(field)) {
                        totalFields++
                    } else if (!optionSets.contains(field.optionSet())) {
                        optionSets.add(field.optionSet())
                        totalFields++
                    }
                }
                if (field is UnsupportedViewModel) unsupportedFields++
            }
        }
    }

    private fun getFieldSection(field: FieldViewModel): String {
        return if (field is DisplayViewModel) {
            DISPLAY_FIELD_KEY
        } else {
            return field.programStageSection() ?: ""
        }
    }

    private fun updateFieldMap(fieldSection: String, field: FieldViewModel) {
        if (!fieldMap.containsKey(fieldSection)) {
            fieldMap[fieldSection] = ArrayList()
        }
        fieldMap[fieldSection]!!.add(field)
    }

    private fun handleSection(
        fields: List<FieldViewModel>,
        sectionList: List<FormSectionViewModel>,
        sectionsToHide: List<String?>,
        sectionModel: FormSectionViewModel,
        section: String
    ) {
        if (isValidMultiSection(sectionList, sectionModel, sectionsToHide)) {
            handleMultiSection(sectionModel, section)
        } else if (isValidSingleSection(sectionList, sectionModel)) {
            handleSingleSection(fields, sectionModel)
        }
    }

    private fun isValidMultiSection(
        sectionList: List<FormSectionViewModel>,
        sectionModel: FormSectionViewModel,
        sectionsToHide: List<String?>
    ): Boolean {
        return sectionList.isNotEmpty() && sectionModel.sectionUid()!!
            .isNotEmpty() && !sectionsToHide.contains(sectionModel.sectionUid())
    }

    private fun isValidSingleSection(
        sectionList: List<FormSectionViewModel>,
        sectionModel: FormSectionViewModel
    ): Boolean {
        return sectionList.size == 1 && sectionModel.sectionUid()?.isEmpty() == true
    }

    private fun handleMultiSection(
        sectionModel: FormSectionViewModel,
        section: String
    ) {
        val fieldViewModels = mutableListOf<FieldViewModel>()
        if (fieldMap[sectionModel.sectionUid()] != null) {
            fieldViewModels.addAll(
                fieldMap[sectionModel.sectionUid()] as Collection<FieldViewModel>
            )
        }

        finalFields = HashMap()
        for (fieldViewModel in fieldViewModels) {
            finalFields[getCorrectUid(fieldViewModel)] =
                !isEmpty(fieldViewModel.value())
        }

        var cont = 0
        for (key in finalFields.keys) if (finalFields[key] == true) cont++
        eventSectionModels.add(
            EventSectionModel.create(
                sectionModel.label()!!,
                sectionModel.sectionUid()!!,
                cont,
                finalFields.keys.size
            )
        )
        val isOpen = sectionModel.sectionUid() == section
        finalFieldList.add(
            SectionViewModel.create(
                sectionModel.sectionUid(),
                sectionModel.label(),
                "",
                isOpen,
                finalFields.keys.size,
                cont,
                sectionModel.renderType()
            )
        )
        if (isOpen && fieldMap[sectionModel.sectionUid()] != null) {
            finalFieldList.addAll(fieldMap[sectionModel.sectionUid()] as Collection<FieldViewModel>)
        }
    }

    private fun handleSingleSection(
        fields: List<FieldViewModel>,
        sectionModel: FormSectionViewModel
    ) {
        for (fieldViewModel in fields) {
            if (fieldViewModel !is DisplayViewModel) {
                finalFields[getCorrectUid(fieldViewModel)] =
                    !isEmpty(fieldViewModel.value())
            }
        }

        var cont = 0
        for (key in finalFields.keys) if (finalFields[key] == true) cont++
        eventSectionModels.add(
            EventSectionModel.create(
                "NO_SECTION",
                "no_section",
                cont,
                finalFields.keys.size
            )
        )
        finalFieldList.addAll(fieldMap[sectionModel.sectionUid()] as Collection<FieldViewModel>)
    }

    private fun getCorrectUid(fieldViewModel: FieldViewModel): String {
        return if (fieldIsNotOptionSetOrImage(fieldViewModel)) {
            fieldViewModel.uid()
        } else {
            fieldViewModel.optionSet()!!
        }
    }

    private fun fieldIsNotOptionSetOrImage(field: FieldViewModel): Boolean {
        return field.optionSet() == null || field !is ImageViewModel
    }
}