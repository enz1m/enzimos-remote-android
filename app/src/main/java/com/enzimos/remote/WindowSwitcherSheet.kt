package com.enzimos.remote

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import com.enzimos.remote.databinding.DialogWindowListBinding
import com.enzimos.remote.webrtc.ServerMessageParser.WindowInfo
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/** Lists compositor windows in most-recently-focused order. */
class WindowSwitcherSheet : BottomSheetDialogFragment() {

    /** Callback when a window is tapped. Receives the window address. */
    var onWindowSelected: ((String) -> Unit)? = null

    private var _binding: DialogWindowListBinding? = null
    private val binding get() = _binding!!

    private var windowList: List<WindowInfo> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = DialogWindowListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val adapter = WindowListAdapter { address ->
            onWindowSelected?.invoke(address)
            dismiss()
        }

        binding.rvWindows.layoutManager = LinearLayoutManager(requireContext())
        binding.rvWindows.adapter = adapter

        if (windowList.isEmpty()) {
            binding.tvEmptyWindows.visibility = View.VISIBLE
            binding.rvWindows.visibility = View.GONE
        } else {
            binding.tvEmptyWindows.visibility = View.GONE
            binding.rvWindows.visibility = View.VISIBLE
            adapter.updateWindows(windowList)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        /**
         * Create a new WindowSwitcherSheet with the given window list.
         *
         * @param windows List of windows from the server's window_list message
         */
        fun newInstance(windows: List<WindowInfo>): WindowSwitcherSheet {
            return WindowSwitcherSheet().apply {
                windowList = windows
            }
        }
    }
}
