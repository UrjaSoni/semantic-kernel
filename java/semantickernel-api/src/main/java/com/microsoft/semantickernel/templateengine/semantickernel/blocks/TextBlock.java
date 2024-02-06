// Copyright (c) Microsoft. All rights reserved.
package com.microsoft.semantickernel.templateengine.semantickernel.blocks;

import com.microsoft.semantickernel.orchestration.contextvariables.KernelArguments;
import javax.annotation.Nullable;

public final class TextBlock extends Block implements TextRendering {

    public TextBlock(String text) {
        super(text, BlockTypes.Text);
    }

    @Override
    public boolean isValid() {
        return true;
    }

    @Override
    public String render(@Nullable KernelArguments variables) {
        return super.getContent();
    }

    public TextBlock(String text, int startIndex, int stopIndex) {
        super(text.substring(startIndex, stopIndex), BlockTypes.Text);
    }
}