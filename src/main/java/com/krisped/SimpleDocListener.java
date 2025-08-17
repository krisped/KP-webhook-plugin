package com.krisped;

import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * Enkel utility som lar deg knytte én Runnable til alle document-endringer.
 */
public abstract class SimpleDocListener implements DocumentListener
{
    private final Runnable onChange;

    private SimpleDocListener(Runnable onChange)
    {
        this.onChange = onChange;
    }

    // Hjelpemetode slik at anonym subklasse kan kalle uten å aksessere privat felt direkte
    protected void fire()
    {
        if (onChange != null)
        {
            onChange.run();
        }
    }

    public static SimpleDocListener onAnyChange(Runnable r)
    {
        return new SimpleDocListener(r)
        {
            @Override public void insertUpdate(DocumentEvent e){ fire(); }
            @Override public void removeUpdate(DocumentEvent e){ fire(); }
            @Override public void changedUpdate(DocumentEvent e){ fire(); }
        };
    }
}