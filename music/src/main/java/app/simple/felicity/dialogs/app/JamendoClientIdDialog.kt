package app.simple.felicity.dialogs.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.FragmentManager
import app.simple.felicity.databinding.DialogJamendoClientIdBinding
import app.simple.felicity.extensions.dialogs.ScopedBottomSheetFragment
import app.simple.felicity.preferences.JamendoPreferences

/**
 * Lets the user paste their own free Jamendo `client_id` (from devportal.jamendo.com) so
 * [app.simple.felicity.repository.repositories.JamendoRepository] can actually make
 * requests — Jamendo requires this on every call and there's no working default that could
 * ship with the app.
 *
 * @author Hamza417
 */
class JamendoClientIdDialog : ScopedBottomSheetFragment() {

    private lateinit var binding: DialogJamendoClientIdBinding

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = DialogJamendoClientIdBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.clientIdInput.setText(JamendoPreferences.getClientId())

        binding.cancel.setOnClickListener {
            dismiss()
        }

        binding.save.setOnClickListener {
            JamendoPreferences.setClientId(binding.clientIdInput.text?.toString().orEmpty())
            dismiss()
        }
    }

    companion object {
        fun newInstance(): JamendoClientIdDialog {
            val args = Bundle()
            val fragment = JamendoClientIdDialog()
            fragment.arguments = args
            return fragment
        }

        fun FragmentManager.showJamendoClientIdDialog() {
            val dialog = newInstance()
            dialog.show(this, TAG)
        }

        const val TAG = "JamendoClientIdDialog"
    }
}
