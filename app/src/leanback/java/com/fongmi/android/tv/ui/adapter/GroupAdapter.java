package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.bean.Group;
import com.fongmi.android.tv.databinding.AdapterGroupBinding;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

public class GroupAdapter extends RecyclerView.Adapter<GroupAdapter.ViewHolder> {

    private final OnClickListener mListener;
    private final List<Group> mItems;
    private final Function<Group, String> mDisplay;

    public GroupAdapter(OnClickListener listener) {
        this(listener, Group::getName);
    }

    public GroupAdapter(OnClickListener listener, Function<Group, String> display) {
        mListener = listener;
        mDisplay = display;
        mItems = new ArrayList<>();
    }

    public void addAll(List<Group> items) {
        if (sameItems(items)) {
            mItems.clear();
            mItems.addAll(items);
            return;
        }
        mItems.clear();
        mItems.addAll(items);
        notifyDataSetChanged();
    }

    public void add(int position, Group item) {
        mItems.add(position, item);
        notifyItemInserted(position);
    }

    public void clear() {
        if (mItems.isEmpty()) return;
        mItems.clear();
        notifyDataSetChanged();
    }

    private boolean sameItems(List<Group> items) {
        if (mItems.size() != items.size()) return false;
        for (int i = 0; i < items.size(); i++) {
            if (!mItems.get(i).getName().equals(items.get(i).getName())) return false;
        }
        return true;
    }

    public Group get(int position) {
        return mItems.get(position);
    }

    public int indexOf(Group item) {
        return mItems.indexOf(item);
    }

    public List<Group> unmodifiableList() {
        return Collections.unmodifiableList(mItems);
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterGroupBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Group item = mItems.get(position);
        holder.binding.name.setText(mDisplay.apply(item));
        holder.binding.getRoot().setOnClickListener(v -> {
            int adapterPosition = holder.getBindingAdapterPosition();
            if (adapterPosition == RecyclerView.NO_POSITION) return;
            mListener.onItemClick(mItems.get(adapterPosition));
        });
    }

    public interface OnClickListener {
        void onItemClick(Group item);
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterGroupBinding binding;

        ViewHolder(@NonNull AdapterGroupBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
