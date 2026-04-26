package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.fongmi.android.tv.bean.Channel;
import com.fongmi.android.tv.databinding.AdapterChannelBinding;

import java.util.ArrayList;
import java.util.List;

public class ChannelAdapter extends RecyclerView.Adapter<ChannelAdapter.ViewHolder> {

    private final OnClickListener mListener;
    private final List<Channel> mItems;
    private int mSelectedPosition;

    public ChannelAdapter(OnClickListener listener) {
        mListener = listener;
        mItems = new ArrayList<>();
        mSelectedPosition = RecyclerView.NO_POSITION;
    }

    public void addAll(List<Channel> items) {
        boolean same = sameItems(items);
        mItems.clear();
        mItems.addAll(items);
        mSelectedPosition = findSelectedPosition();
        if (same) return;
        notifyDataSetChanged();
    }

    public void remove(Channel item) {
        int index = mItems.indexOf(item);
        if (index < 0) return;
        mItems.remove(index);
        notifyItemRemoved(index);
    }

    public void clear() {
        if (mItems.isEmpty()) return;
        mItems.clear();
        mSelectedPosition = RecyclerView.NO_POSITION;
        notifyDataSetChanged();
    }

    public Channel get(int position) {
        return mItems.get(position);
    }

    public void setSelected(Channel selected) {
        int oldPosition = mSelectedPosition;
        int newPosition = RecyclerView.NO_POSITION;
        for (int i = 0; i < mItems.size(); i++) {
            Channel item = mItems.get(i);
            boolean value = item.equals(selected);
            if (value) newPosition = i;
            item.setSelected(value);
        }
        mSelectedPosition = newPosition;
        if (oldPosition == newPosition) return;
        if (oldPosition != RecyclerView.NO_POSITION && oldPosition < mItems.size()) notifyItemChanged(oldPosition);
        if (newPosition != RecyclerView.NO_POSITION) notifyItemChanged(newPosition);
    }

    private boolean sameItems(List<Channel> items) {
        if (mItems.size() != items.size()) return false;
        for (int i = 0; i < items.size(); i++) {
            Channel old = mItems.get(i);
            Channel item = items.get(i);
            if (!old.getName().equals(item.getName())) return false;
            if (!old.getNumber().equals(item.getNumber())) return false;
            if (old.getUrls().size() != item.getUrls().size()) return false;
        }
        return true;
    }

    private int findSelectedPosition() {
        for (int i = 0; i < mItems.size(); i++) if (mItems.get(i).isSelected()) return i;
        return RecyclerView.NO_POSITION;
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterChannelBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Channel item = mItems.get(position);
        holder.binding.name.setText(item.getShow());
        holder.binding.number.setText(item.getNumber());
        holder.binding.getRoot().setSelected(item.isSelected());
        holder.binding.getRoot().setRightListener(() -> {
            int adapterPosition = holder.getBindingAdapterPosition();
            if (adapterPosition == RecyclerView.NO_POSITION) return;
            mListener.showEpg(mItems.get(adapterPosition));
        });
        holder.binding.getRoot().setOnClickListener(v -> {
            int adapterPosition = holder.getBindingAdapterPosition();
            if (adapterPosition == RecyclerView.NO_POSITION) return;
            mListener.onItemClick(mItems.get(adapterPosition));
        });
        holder.binding.getRoot().setOnLongClickListener(v -> {
            int adapterPosition = holder.getBindingAdapterPosition();
            return adapterPosition != RecyclerView.NO_POSITION && mListener.onLongClick(mItems.get(adapterPosition));
        });
    }

    @Override
    public void onViewRecycled(@NonNull ViewHolder holder) {
        Glide.with(holder.binding.logo).clear(holder.binding.logo);
    }

    public interface OnClickListener {

        void showEpg(Channel item);

        void onItemClick(Channel item);

        boolean onLongClick(Channel item);
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterChannelBinding binding;

        ViewHolder(@NonNull AdapterChannelBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
