package com.hedvig.policies.controller

import com.hedvig.policies.dto.CreateInsuranceRequest
import com.hedvig.policies.dto.UpdatePolicyRequest
import com.hedvig.policies.service.PolicyService
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import java.time.LocalDate

@Controller
class WebController(private val policyService: PolicyService) {

    @GetMapping("/")
    fun index(model: Model): String {
        model.addAttribute("title", "Home")
        model.addAttribute("active", "home")
        return "index"
    }

    @GetMapping("/admin/insurances")
    fun listInsurances(model: Model): String {
        val insurances = policyService.getAllInsurances()
        model.addAttribute("insurances", insurances)
        model.addAttribute("title", "All Insurances")
        model.addAttribute("active", "insurances")
        return "insurances/list"
    }

    @GetMapping("/admin/insurances/new")
    fun showCreateForm(model: Model): String {
        return "insurances/create"
    }

    @PostMapping("/admin/insurances")
    fun createInsurance(
        @RequestParam personalNumber: String,
        @RequestParam address: String,
        @RequestParam postalCode: String,
        @RequestParam startDate: String,
        redirectAttributes: RedirectAttributes,
        model: Model
    ): String {
        return try {
            val request = CreateInsuranceRequest(
                personalNumber = personalNumber,
                address = address,
                postalCode = postalCode,
                startDate = LocalDate.parse(startDate)
            )
            val insurance = policyService.createInsurance(request)
            redirectAttributes.addFlashAttribute("success", "Insurance created successfully!")
            "redirect:/admin/insurances/${insurance.id}"
        } catch (e: IllegalArgumentException) {
            model.addAttribute("error", e.message ?: "Failed to create insurance")
            "insurances/create"
        } catch (e: Exception) {
            model.addAttribute("error", "An unexpected error occurred: ${e.message}")
            "insurances/create"
        }
    }

    @GetMapping("/admin/insurances/{id}")
    fun showInsuranceDetail(@PathVariable id: Long, model: Model): String {
        return try {
            val insurance = policyService.getInsurance(id)
            model.addAttribute("insurance", insurance)
            "insurances/detail"
        } catch (e: IllegalArgumentException) {
            "redirect:/admin/insurances"
        }
    }

    @GetMapping("/admin/insurances/{id}/update")
    fun showUpdateForm(@PathVariable id: Long, model: Model): String {
        return try {
            val insurance = policyService.getInsurance(id)
            model.addAttribute("insurance", insurance)

            // Get current active policy
            val currentPolicy = insurance.policies.firstOrNull { it.endDate == null }
            model.addAttribute("currentPolicy", currentPolicy)

            "insurances/update"
        } catch (e: IllegalArgumentException) {
            "redirect:/admin/insurances"
        }
    }

    @PostMapping("/admin/insurances/{id}/update")
    fun updatePolicy(
        @PathVariable id: Long,
        @RequestParam address: String,
        @RequestParam postalCode: String,
        @RequestParam startDate: String,
        redirectAttributes: RedirectAttributes,
        model: Model
    ): String {
        return try {
            val insurance = policyService.getInsurance(id)
            val request = UpdatePolicyRequest(
                address = address,
                postalCode = postalCode,
                startDate = LocalDate.parse(startDate)
            )
            policyService.updatePolicy(insurance.personalNumber, request)
            redirectAttributes.addFlashAttribute("success", "Policy updated successfully!")
            "redirect:/admin/insurances/$id"
        } catch (e: IllegalArgumentException) {
            val insurance = policyService.getInsurance(id)
            model.addAttribute("insurance", insurance)
            model.addAttribute("error", e.message ?: "Failed to update policy")
            "insurances/update"
        } catch (e: Exception) {
            val insurance = policyService.getInsurance(id)
            model.addAttribute("insurance", insurance)
            model.addAttribute("error", "An unexpected error occurred: ${e.message}")
            "insurances/update"
        }
    }
}
