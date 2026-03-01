package io.github.fpiribauer.onepassword_sdk_java

import com.google.gson.Gson

data class ResolveAllIndividualResponseContent(
    val secret: String,
    val itemId: String,
    val vaultId: String,
)

data class ResolveAllIndividualResponse(
    val content: ResolveAllIndividualResponseContent,
    val error: ResolveError?
)

data class ResolveAllResponse(
    val individualResponses: Map<String, ResolveAllIndividualResponse>
)

data class ResolveError(
    val secret_reference: String,
    val error: String
)

data class PasswordRecipe(
    val length: Int? = null,
    val includeDigits: Boolean? = null,
    val includeSymbols: Boolean? = null
)

data class GeneratePasswordResponse(
    val password: String
)

class Secrets(private val inner: InnerClient) {
    private val gson = Gson()

    /**
     * Resolve returns the secret the provided secret reference points to.
     * Reference format: op://vault/item/section/field
     */
    suspend fun resolve(secretReference: String): String {
        val request = mapOf(
            "invocation" to mapOf(
                "clientId" to inner.clientId,
                "parameters" to mapOf(
                    "name" to "SecretsResolve",
                    "parameters" to mapOf("secret_reference" to secretReference)
                )
            )
        )

        val response = inner.core.invoke(request)
        // Rust returns the secret as a JSON string (e.g., "my-password")
        return gson.fromJson(response, String::class.java)
    }

    /**
     * Resolve takes in a list of secret references and returns the secrets they point to.
     */
    suspend fun resolveAll(secretReferences: List<String>): ResolveAllResponse {
        val request = mapOf(
            "invocation" to mapOf(
                "clientId" to inner.clientId,
                "parameters" to mapOf(
                    "name" to "SecretsResolveAll",
                    "parameters" to mapOf("secret_references" to secretReferences)
                )
            )
        )

        val response = inner.core.invoke(request)
        return gson.fromJson(response, ResolveAllResponse::class.java)
    }

}