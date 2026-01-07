# Hedvig Policy Manager

You are to build a very basic [insurance policy](https://en.wikipedia.org/wiki/Insurance_policy) server application, which holds
insurance contracts signed between an insurance provider (Hedvig) and a policyholder (end-users).
For simplicity, we will also be dealing with a stripped-down version of home insurance.

Since this is a code test, we skip important bits such as authentication and user management, and instead will focus
entirely on designing of the APIs and data model of the insurance policies.

If you feel anything is unclear or underspecified, make assumptions and explain your thinking during the interview.

## Getting started

This repo is already set up as a launchable Spring Boot application with enough dependencies to build HTTP endpoints.
The maven `pom.xml` file contains three suggestions for database libraries, where you get to choose the one you are
most comfortable with (or excited to try).

## Requirements

### Data model

To create a valid insurance `Policy`, the following data points need to be collected:

* `personalNumber (string)` - the personal identity number of the policyholder 
* `address (string)` - the street address of the home, like "Kungsgatan 16" 
* `postalCode (string)` - the postal code of the home, like "11135"
* `startDate (date)` - the day this policy begins

#### Insurance timeline

Typically, insurances can be updated by the policyholder with a new `startDate` and updated information — creating
a timeline of multiple back-to-back policies. You can think of this timeline as one `Insurance`, which holds multiple
`Policy` entries.

You can assume that `personalNumber` never changes between policies inside a single insurance.

### Starting an insurance

There should be an endpoint where insurances can be started by providing enough information to create a first policy.

### Reading insurance and policies

There should be ways of reading policies back from the system. Here are some typical use cases for how one typically
queries the insurances:

* List all `Policies` for a given `personalNumber` on a specific `date`
* For a given `Insurance`, show its policy on a specific `date`

### Policy Assistant

Build a chatbot that can answer questions about specific policies using retrieval-augmented generation.

**Functionality:**
- Accept conversational queries: "What's covered under my policy?" or "When does my coverage start?"
- Retrieve relevant policy details from database
- Use LLM with RAG pattern to generate accurate, context-aware responses
- Maintain conversation context across multiple questions

### Claim Data Collection

Build a claim parsing system that extracts structured and relevant data from unstructured text.

**Functionality:**
- Accept unstructured claim submission as input. Example: "I dropped my phone and the screen broke."
- Use an LLM to extract relevant data from the claim, like type of damage, location, etc.
- Validate extracted data
- Calculate confidence score (0.0 to 1.0)

#### LLM API Access

- You may use any of the following (free options available):
    - Google Gemini (free tier recommended)
    - Groq (free tier with rate limits)
    - Ollama with local models (llama3, mistral, etc.)
    - Or paid APIs if you have access (OpenAI, Anthropic, Azure, etc.)
  
## Testing

Feel free to add tests for your system, and write them in the way you feel brings the most value.

## Submitting your solution

Create a private repository on GitHub and invite the reviewers from Hedvig provided by your hiring contact.
