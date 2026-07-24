/*
 * Copyright 2021 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package com.reactnativereadium.reader

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.reactnativereadium.R
import com.reactnativereadium.databinding.FragmentReaderBinding
import com.reactnativereadium.utils.clearPadding
import com.reactnativereadium.utils.hideSystemUi
import com.reactnativereadium.utils.padSystemUi
import com.reactnativereadium.utils.showSystemUi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.readium.r2.navigator.OverflowableNavigator
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.util.DirectionalNavigationAdapter
import org.readium.r2.shared.ExperimentalReadiumApi

/*
 * Adds fullscreen support to the BaseReaderFragment
 */
abstract class VisualReaderFragment : BaseReaderFragment() {

    private lateinit var navigatorFragment: Fragment

    private var _binding: FragmentReaderBinding? = null
    val binding get() = _binding!!

    private var positionLabelManager: PositionLabelManager? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        _binding = FragmentReaderBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        navigatorFragment = navigator as Fragment

        configureTapHandling()

        // Initialize position label manager - simple overlay, matching iOS approach
        positionLabelManager = PositionLabelManager(
            containerView = binding.fragmentReaderContainer,
            publication = model.publication,
            lifecycleScope = viewLifecycleOwner.lifecycleScope
        )

        // Update position label when navigator location changes
        navigator.currentLocator
            .onEach { locator ->
                positionLabelManager?.update(
                    position = locator.locations.position,
                    totalProgression = locator.locations.totalProgression
                )
            }
            .launchIn(viewLifecycleOwner.lifecycleScope)

        childFragmentManager.addOnBackStackChangedListener {
            updateSystemUiVisibility()
        }
        binding.fragmentReaderContainer.setOnApplyWindowInsetsListener { container, insets ->
            updateSystemUiPadding(container, insets)
            insets
        }
    }

    // Vooks: mirror the iOS gesture arbitration. DirectionalNavigationAdapter
    // turns pages on edge taps; a second listener forwards center taps to JS as
    // Event.Tap so the app toggles its own reader chrome. Swipes still page-turn.
    @OptIn(ExperimentalReadiumApi::class)
    private fun configureTapHandling() {
        val overflowNavigator = navigator as? OverflowableNavigator ?: return
        // Narrow edges (15% each side → ~70% center toggle column); 44dp floor
        // keeps 15% honored on phones instead of the 80dp default clamping it.
        // Kept in sync with the iOS pointer policy in ReaderViewController.swift.
        overflowNavigator.addInputListener(
            DirectionalNavigationAdapter(
                overflowNavigator,
                minimumHorizontalEdgeSize = 44.0,
                horizontalEdgeThresholdPercent = 0.15,
                animatedTransition = true,
            )
        )
        overflowNavigator.addInputListener(object : InputListener {
            override fun onTap(event: TapEvent): Boolean {
                viewLifecycleOwner.lifecycleScope.launch {
                    channel.send(ReaderViewModel.Event.Tap)
                }
                return true
            }
        })
    }

    override fun onDestroyView() {
        positionLabelManager?.cleanup()
        positionLabelManager = null
        _binding = null
        super.onDestroyView()
    }

    /**
     * Update the text color of the position label.
     * @param color Android color integer
     */
    fun setPositionLabelColor(color: Int) {
        positionLabelManager?.setTextColor(color)
    }

    fun updateSystemUiVisibility() {
        if (navigatorFragment.isHidden)
            requireActivity().showSystemUi()
        else
            requireActivity().hideSystemUi()

        requireView().requestApplyInsets()
    }

    private fun updateSystemUiPadding(container: View, insets: WindowInsets) {
        if (navigatorFragment.isHidden) {
            container.padSystemUi(insets, requireActivity())
        } else {
            container.clearPadding()
        }
    }
}